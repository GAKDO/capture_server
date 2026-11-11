package com.gakdo.trace.capture.inspection.application;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DefectGroups;
import com.gakdo.trace.capture.inspection.domain.DefectMatchRecord;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.domain.Judgeability;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/**
 * 시점의 흠집·매칭·등급(contracts getCheckpointInspection). 등급이 있으면 그 세트, 없으면 시점의 가장 최근 세트를 보여 준다.
 * 범위: ADMIN은 모두, 일반 고객은 rentals 클레임의 본인 렌탈만(gateway는 경로에 rentalId가 없어 볼 수 없다).
 * 사람 검수(11/8): ADMIN에게는 기각(DISMISSED) 흠집까지 review와 함께 보내고, 고객에게는 기각 흠집을 뺀다.
 */
@Service
public class InspectionQueryService {

    private final CaptureSetRepository sets;
    private final DefectRepository defects;
    private final DefectMatchRepository matches;
    private final GradeResultRepository grades;
    private final FaceAlignmentRepository alignments;

    private final boolean reviewRequired;
    private final boolean holdEmpty;

    /** 검수 켬·후보 없는 세트는 바로 등급(이전 동작). 단위 테스트용. */
    public InspectionQueryService(CaptureSetRepository sets, DefectRepository defects, DefectMatchRepository matches,
            GradeResultRepository grades, FaceAlignmentRepository alignments) {
        this(sets, defects, matches, grades, alignments, true, false);
    }

    /** review.open 계산에 GradingService와 같은 설정(app.review.required·hold-empty)을 쓴다. */
    @Autowired
    public InspectionQueryService(CaptureSetRepository sets, DefectRepository defects, DefectMatchRepository matches,
            GradeResultRepository grades, FaceAlignmentRepository alignments,
            @Value("${app.review.required:true}") boolean reviewRequired,
            @Value("${app.review.hold-empty:true}") boolean holdEmpty) {
        this.sets = sets;
        this.defects = defects;
        this.matches = matches;
        this.grades = grades;
        this.alignments = alignments;
        this.reviewRequired = reviewRequired;
        this.holdEmpty = holdEmpty;
    }

    /**
     * photoDefects(계약 선택 필드): 빠른 검사는 흠집이 여기에만 있고(defects 빈 배열), 전체 검사(11/9)는 원본 px를 아는 흠집을 모두 싣는다
     * (정합에 성공한 흠집은 defects에도 같은 id로 있다). profile·unjudgeableFaces는 11/9 선택 필드로 늘 싣는다.
     */
    public record InspectionView(String checkpointId, String grade, String ruleVersion, List<String> reasons,
            List<DefectView> defects,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<PhotoDefectView> photoDefects, ReviewSummary review,
            String profile, List<UnjudgeableFace> unjudgeableFaces) {
    }

    /** 판정 불가 면과 사람이 읽는 사유(contracts Inspection.unjudgeableFaces). */
    public record UnjudgeableFace(String face, String reason) {
    }

    /** polygon은 늘 표준 좌표 px. */
    public record DefectView(String defectId, String face, @JsonProperty("class") String defectClass, List<List<Double>> polygon,
            Double lengthMm, Double areaMm2, double confidence, String modelVersion, String source, MatchView match,
            ReviewView review) {
    }

    /**
     * 빠른 검사 흠집: 원본 사진 px. 어느 사진인지는 face·light·camera로 찾는다(민서 11/3 결정: 별도 칸).
     * groupId는 같은 면에서 겹치는 흠집 묶음(조명만 다른 사진, 11/9부터 CAM1·CAM2 두 카메라 사진도 합침)의 대표 defectId(등급 계산의 묶기와 같다, 11/8).
     */
    public record PhotoDefectView(String defectId, String face, String light, String camera,
            @JsonProperty("class") String defectClass, List<List<Double>> polygon, double confidence,
            String modelVersion, String groupId, ReviewView review, String source) {
    }

    /** 흠집 하나의 검수 상태(contracts DefectReview). 값이 없으면 null을 그대로 싣는다(계약 required). */
    public record ReviewView(String status, String reason, String reviewedBy, OffsetDateTime reviewedAt) {

        static ReviewView of(Defect d) {
            return new ReviewView(d.getReviewStatus().name(),
                    d.getDismissReason() == null ? null : d.getDismissReason().name(), d.getReviewedBy(),
                    d.getReviewedAt());
        }
    }

    /**
     * 시점의 검수 요약(contracts InspectionReview). 후보가 없던 세트는 모두 0이고 completedAt은 등급 시각.
     * added는 관리자가 직접 더한 흠집 수, open은 지금 검수 칸을 열어야 하는지(11/9).
     */
    public record ReviewSummary(int pending, int confirmed, int dismissed, OffsetDateTime completedAt, int added,
            boolean open) {
    }

    public record MatchView(String status, String prevDefectId, Double distanceMm) {
    }

    /**
     * 시점에서 보여 줄 세트: 등급을 매긴 세트, 없으면 가장 최근 세트. 범위는 ADMIN 전부, 일반 고객은 본인 렌탈만, 촬영 JWT는 403.
     * 미디어 조회(MediaService)도 같은 규칙을 쓴다.
     */
    static CaptureSet visibleSet(Caller caller, String checkpointId, GradeResultRepository grades,
            CaptureSetRepository sets) {
        boolean customerLogin = "CUSTOMER".equals(caller.role()) && !caller.isCaptureToken();
        if (!caller.isAdmin() && !customerLogin) {
            throw new DomainException(ErrorCode.FORBIDDEN, "관리자나 고객 로그인 토큰만 볼 수 있습니다.");
        }
        CaptureSet set = grades.findByCheckpointId(checkpointId).map(GradeResult::getCaptureSet)
                .or(() -> sets.findFirstByCheckpointIdOrderByIdDesc(checkpointId))
                .orElseThrow(() -> DomainException.notFound("시점의 검사 결과"));
        if (!caller.isAdmin() && !caller.ownsRental(set.getRentalId())) {
            throw Caller.outOfScope();
        }
        return set;
    }

    @Transactional(readOnly = true)
    public InspectionView get(Caller caller, String checkpointId) {
        Optional<GradeResult> grade = grades.findByCheckpointId(checkpointId);
        CaptureSet set = visibleSet(caller, checkpointId, grades, sets);

        Map<Long, DefectMatchRecord> byDefect = new HashMap<>();
        matches.findBySet(set).forEach(m -> byDefect.put(m.getDefect().getId(), m));
        List<Defect> all = defects.findBySet(set);
        ReviewSummary review = summary(set, all, grade, ReviewService.open(set, all, reviewRequired, holdEmpty));
        // 고객에게는 사람이 흠집 아님으로 정한 후보를 보이지 않는다
        List<Defect> shown = caller.isAdmin() ? all : all.stream().filter(d -> !d.isDismissed()).toList();
        List<UnjudgeableFace> unjudgeable = unjudgeableFaces(set);
        // 미디어 조회(/media)와 같은 공개 범위: 완료 때 올라간 사진만, 건너뛴 면은 뺀다(등급 계산과 같음).
        Set<Face> skipped = set.getSkippedFaces().keySet();
        List<Defect> visible = shown.stream()
                .filter(d -> d.getPhoto().isUploaded() && !skipped.contains(d.getPhoto().getFace()))
                .filter(d -> d.getPhotoPolygon() != null && d.getPhotoPolygon().size() >= 3)
                .toList();
        Map<String, String> groupOf = groupIds(set, visible);
        List<PhotoDefectView> photo = visible.stream()
                .map(d -> new PhotoDefectView(d.getPublicId(), d.getPhoto().getFace().name(),
                        d.getPhoto().getLight().name(), d.getPhoto().getCamera().name(), d.getDefectClass().code(),
                        d.getPhotoPolygon(), d.getConfidence(), d.getModelVersion(),
                        groupOf.getOrDefault(d.getPublicId(), d.getPublicId()), ReviewView.of(d),
                        d.getSource().name()))
                .toList();
        if (set.isQuick()) {
            return view(checkpointId, grade, List.of(), photo, review, set, unjudgeable);
        }
        List<DefectView> list = shown.stream()
                .filter(d -> d.getPolygon() != null)
                .map(d -> {
                    MatchView match = matchView(byDefect.get(d.getId()));
                    return new DefectView(d.getPublicId(), d.getPhoto().getFace().name(), d.getDefectClass().code(),
                            d.getPolygon(), d.getLengthMm(), d.getAreaMm2(), d.getConfidence(), d.getModelVersion(),
                            d.getSource().name(), match, ReviewView.of(d));
                })
                .toList();
        // 전체 검사(11/9): 사진 위 폴리곤으로 AI가 무엇을 봤는지 보이도록 원본 px 흠집도 함께 싣는다(정합 실패 면의 흠집 포함)
        return view(checkpointId, grade, list, photo, review, set, unjudgeable);
    }

    /**
     * 판정 불가 면(등급 전에도 계산). 전체 검사는 정합 결과를 받은 뒤(INSPECTED·GRADED)에만 정합 실패를 따지고, 그 전에는 건너뛴 면만 보인다.
     * 빠른 검사는 정합을 쓰지 않으므로 건너뛴 면만이다(GradingService.gradeQuickIfReady와 같은 문장).
     */
    List<UnjudgeableFace> unjudgeableFaces(CaptureSet set) {
        Map<Face, String> out;
        boolean inspected = set.getStatus() == SetStatus.INSPECTED || set.getStatus() == SetStatus.GRADED;
        if (!set.isQuick() && inspected) {
            Set<Long> aligned = new HashSet<>();
            alignments.findBySet(set).forEach(a -> aligned.add(a.getPhoto().getId()));
            out = Judgeability.unjudgeable(GradingService.states(set, aligned), set.getSkippedFaces());
        } else {
            out = new java.util.EnumMap<>(Face.class);
            Map<Face, String> target = out;
            set.getSkippedFaces().forEach((face, reason) -> target.put(face, "건너뜀: " + reason));
        }
        return out.entrySet().stream()
                .map(e -> new UnjudgeableFace(e.getKey().name(), cut(e.getValue())))
                .toList();
    }

    private static String cut(String text) {
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** 흠집 id → 같은 면에서 겹치는 묶음(조명·카메라 사이)의 대표 id(첫 흠집). 등급 계산(GradingService.quickGroups)과 같은 묶기. */
    static Map<String, String> groupIds(CaptureSet set, List<Defect> list) {
        Set<Face> faces = EnumSet.noneOf(Face.class);
        set.getPhotos().stream().filter(Photo::isUploaded).forEach(p -> faces.add(p.getFace()));
        Map<String, String> out = new HashMap<>();
        for (DefectGroups.Group g : GradingService.quickGroups(list, faces)) {
            String head = g.defectIds().get(0);
            g.defectIds().forEach(id -> out.put(id, head));
        }
        return out;
    }

    /** 검수 요약: 후보(GradingService.reviewCandidates) 수와 완료 시각. 검수 없이 등급이 났으면 등급 시각을 완료 시각으로 둔다. */
    static ReviewSummary summary(CaptureSet set, List<Defect> all, Optional<GradeResult> grade, boolean open) {
        List<Defect> candidates = GradingService.reviewCandidates(set, all);
        int pending = 0;
        int confirmed = 0;
        int dismissed = 0;
        for (Defect d : candidates) {
            switch (d.getReviewStatus()) {
                case PENDING -> pending++;
                case CONFIRMED -> confirmed++;
                case DISMISSED -> dismissed++;
            }
        }
        OffsetDateTime completedAt = set.getReviewCompletedAt();
        if (completedAt == null) {
            // 검수 완료 기록이 없으면 이 세트로 난 등급의 시각을 완료 시각으로 본다
            completedAt = grade.filter(g -> g.getCaptureSet() != null
                    && java.util.Objects.equals(set.getId(), g.getCaptureSet().getId()))
                    .map(GradeResult::getUpdatedAt)
                    .orElse(null);
        }
        int added = (int) all.stream().filter(Defect::isHuman).count();
        return new ReviewSummary(pending, confirmed, dismissed, completedAt, added, open);
    }

    /** 앞 시점 흠집과의 대응. 대응 기록이 없으면 null, 앞 흠집이 없으면(신규) prevDefectId가 null. */
    private static MatchView matchView(DefectMatchRecord m) {
        if (m == null) {
            return null;
        }
        String prevId = m.getPrevDefect() == null ? null : m.getPrevDefect().getPublicId();
        return new MatchView(m.getStatus().name(), prevId, m.getDistanceMm());
    }

    private static InspectionView view(String checkpointId, Optional<GradeResult> grade, List<DefectView> list,
            List<PhotoDefectView> photo, ReviewSummary review, CaptureSet set, List<UnjudgeableFace> unjudgeable) {
        String profile = set.getProfile().name();
        return grade.map(g -> new InspectionView(checkpointId, g.getGrade().name(), g.getRuleVersion(),
                g.getReasons(), list, photo, review, profile, unjudgeable))
                .orElseGet(() -> new InspectionView(checkpointId, null, null, List.of(), list, photo, review, profile,
                        unjudgeable));
    }
}
