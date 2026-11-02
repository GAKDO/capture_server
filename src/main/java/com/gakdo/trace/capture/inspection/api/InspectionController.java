package com.gakdo.trace.capture.inspection.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.capture.captureset.application.CaptureSetService.PhotoRef;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.inspection.api.InspectionDtos.DetectionBatchRequest;
import com.gakdo.trace.capture.inspection.api.InspectionDtos.DetectionBatchResponse;
import com.gakdo.trace.capture.inspection.application.DetectionService;
import com.gakdo.trace.capture.inspection.application.DetectionService.Detection;
import com.gakdo.trace.capture.inspection.application.InspectionQueryService;
import com.gakdo.trace.capture.inspection.application.InspectionQueryService.InspectionView;
import com.gakdo.trace.capture.inspection.application.MediaService;
import com.gakdo.trace.capture.inspection.application.MediaService.MediaView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/** 검사 (contracts capture-server.yaml 검사 태그): 부스 탐지 저장, 시점 검사 결과, 텍스처 보기 URL. */
@RestController
@Validated
public class InspectionController {

    private final DetectionService detections;
    private final InspectionQueryService inspections;
    private final MediaService media;

    public InspectionController(DetectionService detections, InspectionQueryService inspections, MediaService media) {
        this.detections = detections;
        this.inspections = inspections;
        this.media = media;
    }

    @PostMapping("/api/v1/captures/sets/{setId}/detections")
    public ResponseEntity<DetectionBatchResponse> saveBoothDetections(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String setId,
            @Valid @RequestBody DetectionBatchRequest request) {
        List<Detection> list = request.detections().stream()
                .map(d -> new Detection(new PhotoRef(d.face(), d.light(), d.camera()), d.defectClass(), d.polygon(),
                        d.confidence()))
                .toList();
        int accepted = detections.save(Caller.from(jwt), setId, request.modelVersion(), list);
        return ResponseEntity.status(HttpStatus.CREATED).body(new DetectionBatchResponse(accepted));
    }

    @GetMapping("/api/v1/captures/checkpoints/{checkpointId}/inspection")
    public InspectionView getCheckpointInspection(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return inspections.get(Caller.from(jwt), checkpointId);
    }

    @GetMapping("/api/v1/captures/checkpoints/{checkpointId}/media")
    public MediaView getCheckpointMedia(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return media.get(Caller.from(jwt), checkpointId);
    }
}
