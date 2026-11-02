package com.gakdo.trace.capture.inspection.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** contracts capture-server.yaml DetectionBatchRequest·Detection·DetectionBatchResponse. */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    public record DetectionBatchRequest(
            @NotBlank @Size(max = 64) String modelVersion,
            @NotNull @Valid List<@NotNull @Valid DetectionItem> detections) {
    }

    /** polygon은 그 사진의 원본 px [[x, y], …](꼭짓점 3개 이상). */
    public record DetectionItem(
            @NotNull Face face,
            @NotNull Light light,
            @NotNull Camera camera,
            @JsonProperty("class") @NotNull @Pattern(regexp = "scratch|dent|crack|stain") String defectClass,
            @NotNull @Size(min = 3) List<@NotNull @Size(min = 2, max = 2) List<@NotNull Double>> polygon,
            @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence) {
    }

    public record DetectionBatchResponse(int accepted) {
    }
}
