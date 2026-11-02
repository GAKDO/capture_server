package com.gakdo.trace.capture.inspection.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.application.CaptureSetService.PhotoRef;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
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
        set.markDetectionsReceived();
        log.info("부스 탐지 저장 set={} model={} 개수={} 상태={}", set.getPublicId(), modelVersion, saved.size(),
                set.getStatus());
        grading.gradeIfReady(set);
        return saved.size();
    }
}
