package com.gakdo.trace.capture.inspection.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 받는 이벤트 contracts events/trace.inspect.completed.schema.json 1.x. 모르는 필드는 무시한다(너그러운 소비자, 부 버전이 올라도 읽힘).
 * 필수 값 검사는 {@link InspectionService}가 한다.
 */
public record InspectEvent(
        String schemaVersion,
        String eventId,
        OffsetDateTime occurredAt,
        String rentalId,
        String checkpointId,
        String captureSetId,
        String modelVersion,
        List<Alignment> alignments,
        List<WorkerDefect> defects,
        Map<String, String> textureKeys,
        List<FailedPhoto> failedPhotos) {

    public static final String ROUTING_KEY = "trace.inspect.completed";

    public record Alignment(String photoId, String method, List<List<Double>> homography, Double errorPx,
            String rectifiedKey) {
    }

    public record WorkerDefect(String defectId, String photoId, @JsonProperty("class") String defectClass,
            List<List<Double>> polygon, Double confidence, Double lengthMm, Double areaMm2) {
    }

    public record FailedPhoto(String photoId, String reason) {
    }
}
