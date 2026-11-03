package com.gakdo.trace.capture.inspection.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.Grade;

/**
 * 내는 이벤트 contracts events/trace.checkpoint.graded.schema.json 1.1. 봉투 + 본문.
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
        List<PhotoHash> photos,
        List<Pin> pins,
        boolean compared) {

    public static final String ROUTING_KEY = "trace.checkpoint.graded";
    public static final String SCHEMA_VERSION = "1.1";

    /** 앞 시점과 비교하지 않은 빠른 검사(QUICK)만 compared=false. 그 밖에는 true(계약: 없으면 true). */
    public CheckpointGradedEvent(String schemaVersion, String eventId, OffsetDateTime occurredAt, String rentalId,
            String checkpointId, String captureSetId, CheckpointKind checkpointType, Grade grade, String ruleVersion,
            List<String> newDefectIds, List<Face> unjudgeableFaces, List<PhotoHash> photos, List<Pin> pins) {
        this(schemaVersion, eventId, occurredAt, rentalId, checkpointId, captureSetId, checkpointType, grade,
                ruleVersion, newDefectIds, unjudgeableFaces, photos, pins, true);
    }

    public record PhotoHash(String photoId, String sha256) {
    }

    /** 1.1: 리포트 3D 핀. 같은 흠집 묶음 하나에 하나, 표준 좌표 px 중심. isNew는 newDefectIds에 있으면 true. */
    public record Pin(String defectId, Face face, double x, double y, boolean isNew) {
    }
}
