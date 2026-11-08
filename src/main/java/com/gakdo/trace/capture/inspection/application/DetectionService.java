package com.gakdo.trace.capture.inspection.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.application.CaptureSetService.PhotoRef;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.FaceRules;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.DomainException.FieldError;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.FaceAlignment;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;

/**
 * 부스 1차 탐지 저장(contracts saveBoothDetections, FR-INS-01). 탐지마다 (face, light, camera)로 세트의 사진 한 장을 가리키고
 * polygon은 그 사진의 원본 px다. 같은 세트에 다시 오면 그 세트의 부스 탐지를 통째로 바꾼다(11/2 결정).
 */
@Service
public class DetectionService {

    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    private final CaptureSetRepository sets;
    private final DefectRepository defects;
    private final DefectMatchRepository matches;
    private final FaceAlignmentRepository alignments;
    private final GradingService grading;

    public DetectionService(CaptureSetRepository sets, DefectRepository defects, DefectMatchRepository matches,
            FaceAlignmentRepository alignments, GradingService grading) {
        this.sets = sets;
        this.defects = defects;
        this.matches = matches;
        this.alignments = alignments;
        this.grading = grading;
    }

    /** 탐지 하나. defectClass는 codes.yaml 소문자 코드, polygon은 원본 사진 px. */
    public record Detection(PhotoRef photo, String defectClass, List<List<Double>> polygon, double confidence) {
    }

    /** @return 저장한 탐지 수 */
    @Transactional
    public int save(Caller caller, String setId, String modelVersion, List<Detection> detections) {
        return doSave(caller, setId, modelVersion, detections, null, null, null);
    }

    /**
     * analyzed는 이 요청으로 분석을 마친 사진(없으면 null). 빠른 검사(QUICK) 세트는 필수이고 필수 면(TOP·BOT) 사진을 모두 담아야 한다.
     * 일부 사진만 분석한 결과로 등급을 내고 세트를 닫지 않기 위해서다(contracts #20 리뷰).
     */
    @Transactional
    public int save(Caller caller, String setId, String modelVersion, List<Detection> detections,
            List<PhotoRef> analyzed) {
        return doSave(caller, setId, modelVersion, detections, analyzed, null, null);
    }

    /** inferenceMs는 부스 탐지 시간(선택, 11/6). 성능 그래프에 그대로 나간다. */
    @Transactional
    public int save(Caller caller, String setId, String modelVersion, List<Detection> detections,
            List<PhotoRef> analyzed, Integer inferenceMs) {
        return doSave(caller, setId, modelVersion, detections, analyzed, inferenceMs, null);
    }

    /** boothTotalMs는 부스 촬영 시작 버튼→판정 ms(선택, contracts #30). 성능 그래프에 그대로 나간다. */
    @Transactional
    public int save(Caller caller, String setId, String modelVersion, List<Detection> detections,
            List<PhotoRef> analyzed, Integer inferenceMs, Integer boothTotalMs) {
        return doSave(caller, setId, modelVersion, detections, analyzed, inferenceMs, boothTotalMs);
    }

    /** 두 save의 본문. 트랜잭션은 부른 public 메서드의 것을 쓴다(this로 @Transactional 메서드를 부르지 않기 위해 뺌). */
    private int doSave(Caller caller, String setId, String modelVersion, List<Detection> detections,
            List<PhotoRef> analyzed, Integer inferenceMs, Integer boothTotalMs) {
        if (!caller.isBooth()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "부스 토큰만 탐지 결과를 올릴 수 있습니다.");
        }
        CaptureSet set = sets.findForUpdate(setId).orElseThrow(() -> DomainException.notFound("촬영 세트"));
        if (set.getSource() != CaptureSource.BOOTH || !caller.scope().equals(set.getBoothId())) {
            throw Caller.outOfScope();
        }
        if (set.getStatus() == SetStatus.GRADED) {
            throw new DomainException(ErrorCode.CAPTURE_SET_CLOSED, "이미 등급이 매겨진 세트입니다.");
        }
        if (set.getReviewCompletedAt() != null) {
            // 검수 결정이 가리키는 흠집을 통째로 바꾸면 검수 기록이 사라진다. 검수 전이면 바꿔도 되고, 그때 결정은 새 후보로 다시 한다.
            throw new DomainException(ErrorCode.CAPTURE_SET_CLOSED, "사람 검수를 이미 완료한 세트입니다.");
        }

        Map<PhotoRef, Photo> photos = new HashMap<>();
        set.getPhotos().forEach(p -> photos.put(new PhotoRef(p.getFace(), p.getLight(), p.getCamera()), p));
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < detections.size(); i++) {
            if (!photos.containsKey(detections.get(i).photo())) {
                errors.add(new FieldError("detections[" + i + "]", "세트에 없는 사진입니다(face, light, camera)."));
            }
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "세트에 없는 사진을 가리키는 탐지가 있습니다.", errors);
        }
        if (set.isQuick()) {
            checkAnalyzed(set, photos.keySet(), analyzed);
        }

        // 통째로 바꾸기: 등급 전이라 이 세트 흠집을 가리키는 다른 시점 매칭은 없다
        matches.deleteBySet(set);
        defects.deleteBySet(set);
        List<Defect> saved = detections.stream()
                .map(d -> Defect.fromBooth(photos.get(d.photo()), DefectClass.fromCode(d.defectClass()), d.polygon(),
                        d.confidence(), modelVersion))
                .toList();
        if (set.getStatus() == SetStatus.INSPECTED) {
            Map<Long, FaceAlignment> byPhoto = new HashMap<>();
            alignments.findBySet(set).forEach(a -> byPhoto.put(a.getPhoto().getId(), a));
            GradingService.place(saved, byPhoto);
        }
        defects.saveAll(saved);
        set.markDetectionsReceived(modelVersion, inferenceMs, boothTotalMs);
        log.info("부스 탐지 저장 set={} model={} 개수={} 탐지ms={} 부스전체ms={} 상태={}", set.getPublicId(), modelVersion,
                saved.size(), inferenceMs, boothTotalMs, set.getStatus());
        grading.gradeIfReady(set);
        return saved.size();
    }

    /** 빠른 검사 탐지 요청은 필수 면의 세트 사진을 모두 분석한 결과여야 한다. 아니면 400(analyzedPhotos). */
    static void checkAnalyzed(CaptureSet set, Set<PhotoRef> inSet, List<PhotoRef> analyzed) {
        if (analyzed == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "빠른 검사 세트는 분석한 사진 목록이 필요합니다.",
                    List.of(new FieldError("analyzedPhotos", "TOP·BOT 사진을 모두 분석한 뒤 목록과 함께 보내 주세요")));
        }
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < analyzed.size(); i++) {
            if (!inSet.contains(analyzed.get(i))) {
                errors.add(new FieldError("analyzedPhotos[" + i + "]", "세트에 없는 사진입니다(face, light, camera)."));
            }
        }
        Set<Face> required = FaceRules.required(set.getSource(), set.getProfile());
        Set<PhotoRef> given = new HashSet<>(analyzed);
        List<String> missing = inSet.stream()
                .filter(p -> required.contains(p.face()) && !given.contains(p))
                .map(p -> p.face() + "-" + p.light() + "-" + p.camera())
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            errors.add(new FieldError("analyzedPhotos", "분석하지 않은 사진: " + String.join(", ", missing)));
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "빠른 검사는 TOP·BOT 사진을 모두 분석한 결과만 받습니다.",
                    errors);
        }
    }
}
