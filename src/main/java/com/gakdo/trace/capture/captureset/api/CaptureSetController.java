package com.gakdo.trace.capture.captureset.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.capture.captureset.api.CaptureSetDtos.CaptureSetCompleteRequest;
import com.gakdo.trace.capture.captureset.api.CaptureSetDtos.CaptureSetCreateRequest;
import com.gakdo.trace.capture.captureset.application.CaptureSetResults.CaptureSetCreateResponse;
import com.gakdo.trace.capture.captureset.application.CaptureSetResults.CaptureSetView;
import com.gakdo.trace.capture.captureset.application.CaptureSetService;
import com.gakdo.trace.capture.captureset.application.CaptureSetService.NewPhoto;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.PublicIds;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 촬영 세트 (contracts capture-server.yaml 촬영 태그). 사진 본문은 받지 않는다: JSON만 오가고 사진은 MinIO로 직접 간다. */
@RestController
@Validated
@RequestMapping("/api/v1/captures/sets")
public class CaptureSetController {

    private final CaptureSetService service;

    public CaptureSetController(CaptureSetService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CaptureSetCreateResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CaptureSetCreateRequest request) {
        List<NewPhoto> photos = request.photos().stream()
                .map(p -> new NewPhoto(new PhotoSpec(p.face(), p.light(), p.camera(),
                        "image/png".equals(p.contentType()) ? "png" : "jpg", p.sha256(), p.takenAt()),
                        p.contentType()))
                .toList();
        CaptureSetCreateResponse body = service.create(Caller.from(jwt), request.rentalId(), request.checkpointId(),
                request.source(), photos);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/{setId}/complete")
    public ResponseEntity<CaptureSetView> complete(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String setId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody(required = false) CaptureSetCompleteRequest request) {
        Map<Face, String> skipped = request == null ? Map.of() : request.skippedOrEmpty();
        CaptureSetView body = service.complete(Caller.from(jwt), setId, idempotencyKey, skipped);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/{setId}")
    public CaptureSetView get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String setId) {
        return service.get(Caller.from(jwt), setId);
    }
}
