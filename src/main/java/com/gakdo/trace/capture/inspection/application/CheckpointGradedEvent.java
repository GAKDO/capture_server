package com.gakdo.trace.capture.inspection.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.Grade;

/**
 * 내는 이벤트 contracts events/trace.checkpoint.graded.schema.json 1.0. 봉투 + 본문.
 * report-server(해시 체인 재료·타임라인 등급)와 rental-server(시점 GRADED)가 받는다.
 */
public record CheckpointGradedEvent(
        String schemaVersion,
        String eventId,
        OffsetDateTime occurredAt,
        String rentalId,
        String checkpointId,
        String captureSetId,
        CheckpointKind checkpointType,
        Grade grade,
        String ruleVersion,
        List<String> newDefectIds,
        List<Face> unjudgeableFaces,
        List<PhotoHash> photos) {

    public static final String ROUTING_KEY = "trace.checkpoint.graded";
    public static final String SCHEMA_VERSION = "1.0";

    public record PhotoHash(String photoId, String sha256) {
    }
}
