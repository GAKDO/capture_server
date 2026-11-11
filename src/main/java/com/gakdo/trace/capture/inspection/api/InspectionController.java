package com.gakdo.trace.capture.inspection.api;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.capture.captureset.application.CaptureSetService.PhotoRef;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.inspection.api.InspectionDtos.DetectionBatchRequest;
import com.gakdo.trace.capture.inspection.api.InspectionDtos.DetectionBatchResponse;
import com.gakdo.trace.capture.inspection.api.InspectionDtos.DefectReviewQueue;
import com.gakdo.trace.capture.inspection.api.InspectionDtos.DefectReviewRequest;
import com.gakdo.trace.capture.inspection.api.InspectionDtos.HumanDefectRequest;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.ReviewService.HumanDefect;
import com.gakdo.trace.capture.inspection.application.DetectionService;
import com.gakdo.trace.capture.inspection.application.DetectionService.Detection;
import com.gakdo.trace.capture.inspection.application.InspectionQueryService;
import com.gakdo.trace.capture.inspection.application.InspectionQueryService.InspectionView;
import com.gakdo.trace.capture.inspection.application.MediaService;
import com.gakdo.trace.capture.inspection.application.MediaService.MediaView;
import com.gakdo.trace.capture.inspection.application.ReviewService;
import com.gakdo.trace.capture.inspection.application.ReviewService.Decision;
import com.gakdo.trace.capture.inspection.application.SettlementService;
import com.gakdo.trace.capture.inspection.application.SettlementService.SettlementView;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 검사 (contracts capture-server.yaml 검사 태그): 부스 탐지 저장, 시점 검사 결과, 텍스처 보기 URL, 사람 검수(11/8). */
@RestController
@Validated
public class InspectionController {

    private final DetectionService detections;
    private final InspectionQueryService inspections;
    private final MediaService media;
    private final ReviewService reviews;
    private final SettlementService settlements;

    public InspectionController(DetectionService detections, InspectionQueryService inspections, MediaService media,
            ReviewService reviews, SettlementService settlements) {
        this.detections = detections;
        this.inspections = inspections;
        this.media = media;
        this.reviews = reviews;
        this.settlements = settlements;
    }

    @PostMapping("/api/v1/captures/sets/{setId}/detections")
    public ResponseEntity<DetectionBatchResponse> saveBoothDetections(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String setId,
            @Valid @RequestBody DetectionBatchRequest request) {
        List<Detection> list = request.detections().stream()
                .map(d -> new Detection(new PhotoRef(d.face(), d.light(), d.camera()), d.defectClass(), d.polygon(),
                        d.confidence()))
                .toList();
        // analyzedPhotos는 선택 필드라 빠지면 null 그대로 넘긴다(빠른 검사면 서비스가 400으로 막는다)
        List<PhotoRef> analyzed = Optional.ofNullable(request.analyzedPhotos())
                .map(items -> items.stream().map(a -> new PhotoRef(a.face(), a.light(), a.camera())).toList())
                .orElse(null);
        int accepted = detections.save(Caller.from(jwt), setId, request.modelVersion(), list, analyzed,
                request.inferenceMs(), request.boothTotalMs());
        return ResponseEntity.status(HttpStatus.CREATED).body(new DetectionBatchResponse(accepted));
    }

    @GetMapping("/api/v1/captures/checkpoints/{checkpointId}/inspection")
    public InspectionView getCheckpointInspection(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return inspections.get(Caller.from(jwt), checkpointId);
    }

    /** 반납 정산서(11/10): 반납 시점 신규 흠집 → 규칙표 차감액. */
    @GetMapping("/api/v1/captures/checkpoints/{checkpointId}/settlement")
    public SettlementView getCheckpointSettlement(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return settlements.get(Caller.from(jwt), checkpointId);
    }

    @GetMapping("/api/v1/captures/checkpoints/{checkpointId}/media")
    public MediaView getCheckpointMedia(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return media.get(Caller.from(jwt), checkpointId);
    }

    @GetMapping("/api/v1/captures/reviews")
    public DefectReviewQueue listDefectReviews(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "PENDING") ReviewStatus status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return new DefectReviewQueue(reviews.list(Caller.from(jwt), status, limit));
    }

    @PostMapping("/api/v1/captures/checkpoints/{checkpointId}/reviews")
    public InspectionView saveDefectReviews(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId,
            @Valid @RequestBody DefectReviewRequest request) {
        List<Decision> items = request.items().stream()
                .map(i -> new Decision(i.defectId(), i.decision(), i.reason()))
                .toList();
        return reviews.save(Caller.from(jwt), jwt.getSubject(), checkpointId, items);
    }

    @PostMapping("/api/v1/captures/checkpoints/{checkpointId}/reviews/complete")
    public InspectionView completeDefectReview(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey) {
        return reviews.complete(Caller.from(jwt), jwt.getSubject(), checkpointId, idempotencyKey);
    }

    /** AI가 놓친 흠집 직접 추가(11/9). */
    @PostMapping("/api/v1/captures/checkpoints/{checkpointId}/reviews/additions")
    public ResponseEntity<InspectionView> addHumanDefect(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId,
            @Valid @RequestBody HumanDefectRequest request) {
        HumanDefect defect = new HumanDefect(request.face(), request.light(), request.camera(),
                DefectClass.fromCode(request.defectClass()), request.polygon());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviews.add(Caller.from(jwt), jwt.getSubject(), checkpointId, defect));
    }

    /** 직접 추가한 흠집 지우기(11/9). */
    @DeleteMapping("/api/v1/captures/checkpoints/{checkpointId}/reviews/additions/{defectId}")
    public InspectionView deleteHumanDefect(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String defectId) {
        return reviews.remove(Caller.from(jwt), jwt.getSubject(), checkpointId, defectId);
    }
}
