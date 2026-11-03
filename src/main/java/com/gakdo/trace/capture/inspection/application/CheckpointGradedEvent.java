package com.gakdo.trace.capture.inspection.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.Grade;

/**
 * 내는 이벤트 contracts events/trace.checkpoint.graded.schema.json 1.2. 봉투 + 본문.
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
    public static final String SCHEMA_VERSION = "1.2";

    /** compared를 안 주면 출고(OUT)는 false(비교 대상 없음), 그 밖에는 true. 1.2 계약의 '없을 때' 규칙과 같다. */
    public CheckpointGradedEvent(String schemaVersion, String eventId, OffsetDateTime occurredAt, String rentalId,
            String checkpointId, String captureSetId, CheckpointKind checkpointType, Grade grade, String ruleVersion,
            List<String> newDefectIds, List<Face> unjudgeableFaces, List<PhotoHash> photos, List<Pin> pins) {
        this(schemaVersion, eventId, occurredAt, rentalId, checkpointId, captureSetId, checkpointType, grade,
                ruleVersion, newDefectIds, unjudgeableFaces, photos, pins, checkpointType != CheckpointKind.OUT);
    }

    public record PhotoHash(String photoId, String sha256) {
    }

    /** 1.1: 리포트 3D 핀. 같은 흠집 묶음 하나에 하나, 표준 좌표 px 중심. isNew는 newDefectIds에 있으면 true. */
    public record Pin(String defectId, Face face, double x, double y, boolean isNew) {
    }
}
