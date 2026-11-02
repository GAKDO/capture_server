package com.gakdo.trace.capture.captureset.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.common.infra.PublicIds;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** contracts openapi/capture-server.yaml의 촬영 요청 스키마. 응답은 application.CaptureSetResults. */
public final class CaptureSetDtos {

    static final String SHA256 = "^[0-9a-f]{64}$";

    private CaptureSetDtos() {
    }

    public record PhotoSpecRequest(
            @NotNull Face face,
            @NotNull Light light,
            @NotNull Camera camera,
            @NotNull @Pattern(regexp = SHA256, message = "SHA-256 16진수 소문자 64자여야 합니다") String sha256,
            @NotNull @Pattern(regexp = "image/jpeg|image/png", message = "image/jpeg 또는 image/png여야 합니다") String contentType,
            @NotNull OffsetDateTime takenAt) {
    }

    public record CaptureSetCreateRequest(
            @NotNull @Pattern(regexp = PublicIds.PATTERN, message = "NanoID 21자여야 합니다") String rentalId,
            @NotNull @Pattern(regexp = PublicIds.PATTERN, message = "NanoID 21자여야 합니다") String checkpointId,
            @NotNull CaptureSource source,
            @NotNull @Size(min = 1, max = 54) List<@Valid @NotNull PhotoSpecRequest> photos) {
    }

    public record CaptureSetCompleteRequest(
            Map<Face, @NotBlank @Size(max = 200) String> skippedFaces) {

        public Map<Face, String> skippedOrEmpty() {
            return skippedFaces == null ? Map.of() : skippedFaces;
        }
    }
}
