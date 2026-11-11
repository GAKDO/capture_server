package com.gakdo.trace.capture.inspection.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.inspection.domain.DismissReason;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.ReviewQueueRepository.QueueRow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** contracts capture-server.yaml DetectionBatchRequest·Detection·DetectionBatchResponse, 사람 검수(DefectReviewRequest·DefectReviewQueue). */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    public record DetectionBatchRequest(
            @NotBlank @Size(max = 64) String modelVersion,
            @NotNull @Valid List<@NotNull @Valid DetectionItem> detections,
            @Size(max = 80) @Valid List<@NotNull @Valid PhotoRefItem> analyzedPhotos,
            @Min(0) @Max(600_000) Integer inferenceMs,
            @Min(0) @Max(3_600_000) Integer boothTotalMs) {
    }

    /** 분석을 마친 사진 하나(11/3 추가). 빠른 검사 세트는 TOP·BOT 사진 전부를 담아야 한다. */
    public record PhotoRefItem(@NotNull Face face, @NotNull Light light, @NotNull Camera camera) {
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

    /** 검수 결정 묶음(11/8). 한 요청 1~200개. */
    public record DefectReviewRequest(@NotNull @Size(min = 1, max = 200) @Valid List<@NotNull @Valid ReviewItem> items) {
    }

    /** reason은 DISMISSED에만(서비스가 400으로 가린다). */
    public record ReviewItem(
            @NotNull @Pattern(regexp = PublicIds.PATTERN) String defectId,
            @NotNull ReviewStatus decision,
            DismissReason reason) {
    }

    /** 관리자가 원본 사진 한 장 위에 그린 흠집(11/9). polygon은 원본 px 꼭짓점 3~200개(서비스가 좌표 범위를 다시 본다). */
    public record HumanDefectRequest(
            @NotNull Face face,
            @NotNull Light light,
            @NotNull Camera camera,
            @JsonProperty("class") @NotNull @Pattern(regexp = "scratch|dent|crack|stain") String defectClass,
            @NotNull @Size(min = 3, max = 200) List<@NotNull @Size(min = 2, max = 2) List<@NotNull Double>> polygon) {
    }

    public record DefectReviewQueue(List<QueueRow> items) {
    }
}
