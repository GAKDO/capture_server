package com.gakdo.trace.capture.captureset.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.DeviceModel;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;

/**
 * contracts events/trace.capture.uploaded.schema.json 1.0. 봉투(schemaVersion·eventId·occurredAt·rentalId·checkpointId) +
 * 본문. needDetection은 폰이면 true(부스는 Jetson이 탐지해 REST로 올림). device(기종·치수)와 skippedFaces는 없으면 생략한다.
 */
public record CaptureUploadedEvent(
        String schemaVersion,
        String eventId,
        OffsetDateTime occurredAt,
        String rentalId,
        String checkpointId,
        String captureSetId,
        CaptureSource source,
        boolean needDetection,
        List<EventPhoto> photos,
        @JsonInclude(JsonInclude.Include.NON_NULL) DeviceModel device,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<Face, String> skippedFaces) {

    public static final String ROUTING_KEY = "trace.capture.uploaded";
    public static final String SCHEMA_VERSION = "1.0";

    public record EventPhoto(String photoId, Face face, Light light, Camera camera, String objectKey, String sha256) {
    }
}
