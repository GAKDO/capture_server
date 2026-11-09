package com.gakdo.trace.capture.inspection.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.comparison.domain.ComparisonResult;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.comparison.domain.DefectMatch;
import com.gakdo.trace.capture.comparison.domain.DefectObservation;
import com.gakdo.trace.capture.comparison.domain.MatchRule;
import com.gakdo.trace.capture.comparison.domain.MatchStatus;
import com.gakdo.trace.capture.comparison.domain.ViewComparator;
import com.gakdo.trace.capture.comparison.domain.ViewSnapshot;
import com.gakdo.trace.capture.inspection.application.CheckpointGradedEvent.PhotoHash;
import com.gakdo.trace.capture.inspection.application.CheckpointGradedEvent.Pin;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DefectGroups;
import com.gakdo.trace.capture.inspection.domain.DefectMatchRecord;
import com.gakdo.trace.capture.inspection.domain.DefectSource;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.domain.FaceAlignment;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.domain.GradeRules;
import com.gakdo.trace.capture.inspection.domain.Judgeability;
import com.gakdo.trace.capture.inspection.domain.Judgeability.PhotoState;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/**
 * 사람 검수(11/8): AI 후보가 있는 세트는 관리자가 검수를 완료할 때까지 등급을 미루고, 기각(DISMISSED) 흠집은 등급·비교에서 뺀다.
 * 판정 불가 면 → 바로 앞 시점과 비교(C의 view-match-v1) → 같은 흠집 묶기 → 등급(grade-rules-v1) → 저장·이벤트.
 * 부르는 쪽이 세트 행을 잠근 트랜잭션 안에서 부른다(탐지 POST와 정합 이벤트가 동시에 와도 한 번만 매긴다).
 */
@Service
public class GradingService {

    private static final Logger log = LoggerFactory.getLogger(GradingService.class);
    private static final MatchRule RULE = MatchRule.V1;
    /** 계약 trace.checkpoint.graded pins maxItems */
    static final int MAX_PINS = 500;

    private final DefectRepository defects;
    private final DefectMatchRepository matches;
    private final FaceAlignmentRepository alignments;
    private final GradeResultRepository grades;
    private final GradeEventPublisher events;
    private final boolean reviewRequired;
    private final ViewComparator comparator = new ViewComparator(RULE);

    /** 검수 없이 바로 등급을 매긴다(검수 이전 동작, 단위 테스트용). */
    public GradingService(DefectRepository defects, DefectMatchRepository matches, FaceAlignmentRepository alignments,
            GradeResultRepository grades, GradeEventPublisher events) {
        this(defects, matches, alignments, grades, events, false);
    }

    /** reviewRequired(app.review.required, 기본 true)가 false면 후보가 있어도 검수를 기다리지 않는다(검수 화면 배포 전 되돌리기용). */
    @Autowired
    public GradingService(DefectRepository defects, DefectMatchRepository matches, FaceAlignmentRepository alignments,
            GradeResultRepository grades, GradeEventPublisher events,
            @Value("${app.review.required:true}") boolean reviewRequired) {
        this.defects = defects;
        this.matches = matches;
        this.alignments = alignments;
        this.grades = grades;
        this.events = events;
        this.reviewRequired = reviewRequired;
    }

    public boolean isReviewRequired() {
        return reviewRequired;
    }

    /**
     * 검수할 AI 후보: 모델이 낸(AUTO) 흠집 중 완료 때 올라갔고 건너뛰지 않은 면의 것. 전체 세트는 표준 좌표가 있어야 한다(정합하지 못한
     * 사진의 흠집은 어차피 등급에 쓰지 않는다). 검수 대기 목록 SQL(ReviewQueueRepository)과 같은 조건이다.
     */
    public static List<Defect> reviewCandidates(CaptureSet set, List<Defect> all) {
        Set<Face> skipped = set.getSkippedFaces().keySet();
        return all.stream()
                .filter(d -> d.getSource() == DefectSource.AUTO)
                .filter(d -> d.getPhoto().isUploaded() && !skipped.contains(d.getPhoto().getFace()))
                .filter(d -> set.isQuick() || d.getPolygon() != null)
                .toList();
    }

    /** 후보가 있는데 아직 검수를 완료하지 않았으면 true(등급을 미룬다). */
    private boolean awaitingReview(CaptureSet set, List<Defect> all) {
        if (!reviewRequired || set.getReviewCompletedAt() != null) {
            return false;
        }
        List<Defect> candidates = reviewCandidates(set, all);
        if (candidates.isEmpty()) {
            return false;
        }
        long pending = candidates.stream().filter(d -> d.getReviewStatus() == ReviewStatus.PENDING).count();
        log.info("사람 검수를 기다립니다 set={} 후보={} 남은 것={}", set.getPublicId(), candidates.size(), pending);
        return true;
    }

    /** 검수를 완료한 세트면 이벤트에 실을 검수 기록, 아니면 null(후보가 없던 세트·검수 끔). */
    private static CheckpointGradedEvent.Review reviewOf(CaptureSet set, List<Defect> all) {
        if (set.getReviewCompletedAt() == null) {
            return null;
        }
        List<Defect> candidates = reviewCandidates(set, all);
        int confirmed = (int) candidates.stream().filter(d -> d.getReviewStatus() == ReviewStatus.CONFIRMED).count();
        int dismissed = (int) candidates.stream().filter(Defect::isDismissed).count();
        return new CheckpointGradedEvent.Review(set.getReviewCompletedBy(), set.getReviewCompletedAt(), confirmed,
                dismissed);
    }

    private static List<Defect> notDismissed(List<Defect> all) {
        return all.stream().filter(d -> !d.isDismissed()).toList();
    }

    /**
     * 준비됐으면 등급을 매긴다. 정합 결과(INSPECTED)가 있어야 하고, 부스 세트는 부스 탐지도 와 있어야 한다.
     *
     * @return 등급을 매겼으면 true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean gradeIfReady(CaptureSet set) {
        if (set.isQuick()) {
            return gradeQuickIfReady(set);
        }
        if (set.getStatus() != SetStatus.INSPECTED) {
            return false;
        }
        if (set.getSource() == CaptureSource.BOOTH && set.getDetectionsReceivedAt() == null) {
            log.info("부스 탐지를 기다립니다 set={}", set.getPublicId());
            return false;
        }

        Set<Long> aligned = new HashSet<>();
        alignments.findBySet(set).forEach(a -> aligned.add(a.getPhoto().getId()));
        List<PhotoState> states = states(set, aligned);
        Map<Face, String> unjudgeable = Judgeability.unjudgeable(states, set.getSkippedFaces());
        Set<Face> judgeable = Judgeability.judgeable(states, unjudgeable);
        if (judgeable.isEmpty()) {
            set.markUnjudgeable();
            log.warn("판정할 수 있는 면이 없어 등급을 매기지 않습니다 set={} 사유={}", set.getPublicId(), unjudgeable);
            return false;
        }

        List<Defect> all = defects.findBySet(set);
        if (awaitingReview(set, all)) {
            return false;
        }
        List<Defect> current = usable(all, judgeable);
        // 빠른 검사 결과는 표준 좌표 흠집이 없어 비교할 수 없으므로 전체 세트 결과만 앞 시점으로 쓴다.
        Optional<GradeResult> previous = grades
                .findFirstByCaptureSetRentalIdAndCaptureSetProfileAndCheckpointIdNotOrderByUpdatedAtDesc(
                        set.getRentalId(), CaptureProfile.FULL, set.getCheckpointId());
        Map<String, Defect> prevById = new HashMap<>();
        ViewSnapshot prevSnapshot = previous.map(p -> snapshotOf(p, prevById)).orElse(null);

        ComparisonResult comparison = comparator.compare(prevSnapshot, snapshot(current, judgeable));
        Map<String, Defect> currentById = new HashMap<>();
        current.forEach(d -> currentById.put(d.getPublicId(), d));
        matches.deleteBySet(set);
        Map<String, MatchStatus> status = new HashMap<>();
        List<String> newIds = new ArrayList<>();
        for (DefectMatch m : comparison.matches()) {
            Defect prev = m.prevDefectId() == null ? null : prevById.get(m.prevDefectId());
            matches.save(new DefectMatchRecord(currentById.get(m.defectId()), prev, m.status(), m.distanceMm(),
                    comparison.ruleVersion()));
            status.put(m.defectId(), m.status());
            if (m.status() == MatchStatus.NEW) {
                newIds.add(m.defectId());
            }
        }

        List<DefectGroups.Item> items = current.stream()
                .map(d -> new DefectGroups.Item(observe(d), d.getLengthMm(), status.get(d.getPublicId())))
                .toList();
        List<DefectGroups.Group> groups = DefectGroups.group(items, RULE);
        GradeRules.Outcome outcome = GradeRules.evaluate(groups, unjudgeable, previous.isEmpty());

        GradeResult result = grades.findByCheckpointId(set.getCheckpointId())
                .orElseGet(() -> new GradeResult(set.getCheckpointId()));
        List<Face> unjudgeableFaces = List.copyOf(unjudgeable.keySet());
        result.recordGrade(set, outcome.grade(), outcome.ruleVersion(), outcome.reasons(), unjudgeableFaces);
        grades.save(result);
        set.markGraded();

        CheckpointKind kind = kindOf(set, result);
        events.publishGraded(new CheckpointGradedEvent(CheckpointGradedEvent.SCHEMA_VERSION, PublicIds.next(),
                Times.now(), set.getRentalId(), set.getCheckpointId(), set.getPublicId(), kind, outcome.grade(),
                outcome.ruleVersion(), newIds, unjudgeableFaces, photoHashes(set), pins(groups, currentById, newIds),
                previous.isPresent(), reviewOf(set, all)));
        if (log.isInfoEnabled()) {
            log.info("등급 set={} checkpoint={} type={} grade={} 신규={} 판정불가={} 비교={}", set.getPublicId(),
                    set.getCheckpointId(), kind, outcome.grade(), newIds.size(), unjudgeableFaces,
                    previous.map(GradeResult::getCheckpointId).orElse("없음(기준선)"));
        }
        return true;
    }

    /**
     * 빠른 검사(QUICK, 11/3 MVP): 완료(UPLOADED)와 부스 탐지가 둘 다 있으면 정합 없이 등급을 매긴다.
     * 사람 검수를 기다리는 동안 inspect-worker 정합이 와서 INSPECTED가 됐어도 매긴다(정합은 쓰지 않음).
     * 흠집은 원본 사진 px라 거리로 묶지 않고 하나씩 세며(길이 모름), 앞 시점과 비교하지 않는다. 판정 불가 면은 올라가지 않았거나
     * 건너뛴 면이다. 핀은 표준 좌표가 없어 싣지 않는다.
     */
    boolean gradeQuickIfReady(CaptureSet set) {
        if (!set.isQuickGradable()) {
            return false;
        }
        if (set.getDetectionsReceivedAt() == null) {
            log.info("빠른 검사: 부스 탐지를 기다립니다 set={}", set.getPublicId());
            return false;
        }
        Set<Face> uploaded = EnumSet.noneOf(Face.class);
        set.getPhotos().stream().filter(Photo::isUploaded).forEach(p -> uploaded.add(p.getFace()));
        Map<Face, String> unjudgeable = new EnumMap<>(Face.class);
        set.getSkippedFaces().forEach((face, reason) -> unjudgeable.put(face, "건너뜀: " + reason));
        uploaded.removeAll(unjudgeable.keySet());
        if (uploaded.isEmpty()) {
            set.markUnjudgeable();
            log.warn("빠른 검사: 판정할 면이 없습니다 set={}", set.getPublicId());
            return false;
        }

        List<Defect> all = defects.findBySet(set);
        if (awaitingReview(set, all)) {
            return false;
        }
        List<DefectGroups.Group> groups = quickGroups(notDismissed(all), uploaded);
        Optional<GradeResult> previous = grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(
                set.getRentalId(), set.getCheckpointId());
        boolean baseline = previous.isEmpty();
        GradeRules.Outcome outcome = GradeRules.evaluate(groups, unjudgeable, baseline);
        List<String> reasons = new ArrayList<>(outcome.reasons());
        reasons.add("빠른 검사(위·아래 " + uploaded.size() + "면, 정합·위치 비교 없음)");
        previous.map(p -> quickCountChange(p, groups)).ifPresent(reasons::add);

        GradeResult result = grades.findByCheckpointId(set.getCheckpointId())
                .orElseGet(() -> new GradeResult(set.getCheckpointId()));
        List<Face> unjudgeableFaces = List.copyOf(unjudgeable.keySet());
        result.recordGrade(set, outcome.grade(), outcome.ruleVersion(), List.copyOf(reasons), unjudgeableFaces);
        grades.save(result);
        set.markGradedQuick();

        CheckpointKind kind = kindOf(set, result);
        events.publishGraded(new CheckpointGradedEvent(CheckpointGradedEvent.SCHEMA_VERSION, PublicIds.next(),
                Times.now(), set.getRentalId(), set.getCheckpointId(), set.getPublicId(), kind, outcome.grade(),
                outcome.ruleVersion(), List.of(), unjudgeableFaces, photoHashes(set), List.of(), false,
                reviewOf(set, all)));
        log.info("빠른 검사 등급 set={} checkpoint={} grade={} 흠집={} 판정불가={}", set.getPublicId(),
                set.getCheckpointId(), outcome.grade(), groups.size(), unjudgeableFaces);
        return true;
    }

    /**
     * 빠른 검사 흠집 묶기: 같은 면·같은 카메라 사진끼리만 원본 px로 묶는다. 부스 카메라는 고정이라 조명(L1·L2·L3)만 다른 사진은
     * 좌표가 같아, 두 조명에 함께 잡힌 같은 흠집을 한 번만 센다. 다른 카메라 사진은 좌표계가 달라 합치지 않는다.
     * 묶는 거리는 비교 규칙과 같은 20 px(표준 좌표 5 mm 자리)를 원본 px에 그대로 쓴다. 길이는 몰라 null, 비교 없음.
     */
    static List<DefectGroups.Group> quickGroups(List<Defect> all, Set<Face> judgeable) {
        Map<String, List<DefectGroups.Item>> byPhotoFrame = new java.util.LinkedHashMap<>();
        for (Defect d : all) {
            Face face = d.getPhoto().getFace();
            if (!judgeable.contains(face) || d.getPhotoPolygon() == null || d.getPhotoPolygon().size() < 3) {
                continue;
            }
            byPhotoFrame.computeIfAbsent(face + "-" + d.getPhoto().getCamera(), k -> new ArrayList<>())
                    .add(new DefectGroups.Item(DefectObservation.fromStandardPx(d.getPublicId(), face,
                            d.getDefectClass(), d.getPhotoPolygon()), null, null));
        }
        List<DefectGroups.Group> groups = new ArrayList<>();
        byPhotoFrame.values().forEach(items -> groups.addAll(DefectGroups.group(items, RULE)));
        return groups;
    }

    /**
     * 시점 종류: 같은 렌탈에서 이 시점보다 먼저 등급이 난 다른 시점 수로 정한다(부스·폰 출처와 무관). 이 시점의 첫 등급 시각을 기준으로
     * 세므로, 뒤 시점이 등급 난 뒤 앞 시점을 다시 찍어도(재등급) 앞 시점 종류가 바뀌지 않는다.
     */
    private CheckpointKind kindOf(CaptureSet set, GradeResult result) {
        return CheckpointKind.ofPosition(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(
                set.getRentalId(), set.getCheckpointId(), result.getCreatedAt()));
    }

    /**
     * 빠른 검사 시점 비교(개수만): 앞 시점도 빠른 검사면 같은 방식으로 센 흠집 수(오염 제외)를 나란히 적는다. 위치로 같은 흠집인지는
     * 모르므로(기기를 놓는 자리가 매번 다름) 신규 판정·등급에는 쓰지 않고 근거 문장으로만 보인다. 앞 시점이 전체 세트면 세는 법이 달라 적지 않는다.
     */
    private String quickCountChange(GradeResult previous, List<DefectGroups.Group> current) {
        CaptureSet prevSet = previous.getCaptureSet();
        if (prevSet == null || prevSet.getProfile() != CaptureProfile.QUICK) {
            return null;
        }
        Set<Face> prevFaces = EnumSet.noneOf(Face.class);
        prevSet.getPhotos().stream().filter(Photo::isUploaded).forEach(p -> prevFaces.add(p.getFace()));
        prevFaces.removeAll(prevSet.getSkippedFaces().keySet());
        long before = countNonStain(quickGroups(notDismissed(defects.findBySet(prevSet)), prevFaces));
        long now = countNonStain(current);
        long diff = now - before;
        String change = diff > 0 ? diff + "개 늘어남" : diff < 0 ? (-diff) + "개 줄어듦" : "같음";
        return "앞 시점 비교(개수만): 흠집 " + before + "개 → " + now + "개(" + change + ", 오염 제외)";
    }

    private static long countNonStain(List<DefectGroups.Group> groups) {
        return groups.stream().filter(g -> g.defectClass() != DefectClass.STAIN).count();
    }

    static List<PhotoState> states(CaptureSet set, Set<Long> aligned) {
        return set.getPhotos().stream()
                .map(p -> new PhotoState(p.getFace(), p.getCamera(), p.isUploaded(), p.isQualityOk(),
                        aligned.contains(p.getId()), p.getAlignFailure()))
                .toList();
    }

    /** 표준 좌표가 있고 판정 가능 면에 있으며 사람이 기각하지 않은 흠집만 비교·등급에 쓴다. */
    private static List<Defect> usable(List<Defect> all, Set<Face> judgeable) {
        return all.stream()
                .filter(d -> d.getPolygon() != null && judgeable.contains(d.getPhoto().getFace()))
                .filter(d -> !d.isDismissed())
                .toList();
    }

    /** 앞 시점: 그때 등급을 매긴 세트의 흠집과, 그 세트에서 판정할 수 있던 면. */
    private ViewSnapshot snapshotOf(GradeResult previous, Map<String, Defect> prevById) {
        CaptureSet prevSet = previous.getCaptureSet();
        Set<Face> faces = EnumSet.noneOf(Face.class);
        prevSet.getPhotos().stream().filter(Photo::isUploaded).forEach(p -> faces.add(p.getFace()));
        faces.removeAll(previous.getUnjudgeableFaces());
        faces.removeAll(prevSet.getSkippedFaces().keySet());
        List<Defect> prevDefects = usable(defects.findBySet(prevSet), faces);
        prevDefects.forEach(d -> prevById.put(d.getPublicId(), d));
        return snapshot(prevDefects, faces);
    }

    private static ViewSnapshot snapshot(List<Defect> list, Set<Face> faces) {
        return new ViewSnapshot(list.stream().map(GradingService::observe).toList(), faces);
    }

    private static DefectObservation observe(Defect d) {
        return DefectObservation.fromStandardPx(d.getPublicId(), d.getPhoto().getFace(), d.getDefectClass(),
                d.getPolygon());
    }

    /**
     * 묶음마다 핀 하나. 대표 흠집은 isNew와 newDefectIds가 어긋나지 않게 고른다
     * (신규 묶음이면 첫 NEW 흠집, 아니면 첫 NEW가 아닌 흠집). 위치는 대표 흠집 다각형 꼭짓점의 평균(표준 px).
     */
    static List<Pin> pins(List<DefectGroups.Group> groups, Map<String, Defect> byId, List<String> newIds) {
        Set<String> fresh = new HashSet<>(newIds);
        List<Pin> pins = new ArrayList<>();
        for (DefectGroups.Group g : groups) {
            boolean isNew = Boolean.TRUE.equals(g.isNew());
            String id = g.defectIds().stream().filter(d -> fresh.contains(d) == isNew).findFirst()
                    .orElse(g.defectIds().get(0));
            Defect d = byId.get(id);
            if (d == null || d.getPolygon() == null || d.getPolygon().isEmpty()) {
                continue;
            }
            double x = 0;
            double y = 0;
            for (List<Double> point : d.getPolygon()) {
                x += point.get(0);
                y += point.get(1);
            }
            int n = d.getPolygon().size();
            pins.add(new Pin(id, g.face(), Math.max(0, x / n), Math.max(0, y / n), fresh.contains(id)));
        }
        return limit(pins, MAX_PINS);
    }

    /**
     * 핀은 화면용이라 상한을 넘으면 잘라 계약(maxItems)을 지킨다. 새 흠집 핀을 먼저 남기고, 같은 종류 안에서는 원래 순서를 지킨다.
     * 등급·newDefectIds는 그대로라 판정에는 영향이 없다.
     */
    static List<Pin> limit(List<Pin> pins, int max) {
        if (pins.size() <= max) {
            return pins;
        }
        List<Pin> kept = new ArrayList<>(max);
        pins.stream().filter(Pin::isNew).limit(max).forEach(kept::add);
        pins.stream().filter(p -> !p.isNew()).limit((long) max - kept.size()).forEach(kept::add);
        log.warn("핀이 {}개라 {}개만 싣습니다(새 흠집 우선)", pins.size(), max);
        return kept;
    }

    /** 해시 체인 재료: 올라갔고 건너뛰지 않은 사진. */
    private static List<PhotoHash> photoHashes(CaptureSet set) {
        return set.getPhotos().stream()
                .filter(Photo::isUploaded)
                .filter(p -> !set.getSkippedFaces().containsKey(p.getFace()))
                .map(p -> new PhotoHash(p.getPublicId(), p.getSha256()))
                .toList();
    }

    /** 정합 결과로 표준 좌표를 채운다(부스 탐지). 정합하지 못한 사진의 흠집은 표준 좌표 없이 남는다. */
    static void place(List<Defect> list, Map<Long, FaceAlignment> byPhoto) {
        for (Defect d : list) {
            FaceAlignment a = byPhoto.get(d.getPhoto().getId());
            if (a != null) {
                d.place(a.toHomography());
            }
        }
    }
}
