package com.gakdo.trace.capture.inspection.application;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.Grade;

/**
 * 내는 이벤트 contracts events/trace.checkpoint.graded.schema.json 1.3. 봉투 + 본문. 1.3은 선택 필드 review(사람 검수)만 더했다.
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
        boolean compared,
        @JsonInclude(JsonInclude.Include.NON_NULL) Review review) {

    public static final String ROUTING_KEY = "trace.checkpoint.graded";
    public static final String SCHEMA_VERSION = "1.3";

    /** 검수 기록 없이(후보가 없던 세트). */
    public CheckpointGradedEvent(String schemaVersion, String eventId, OffsetDateTime occurredAt, String rentalId,
            String checkpointId, String captureSetId, CheckpointKind checkpointType, Grade grade, String ruleVersion,
            List<String> newDefectIds, List<Face> unjudgeableFaces, List<PhotoHash> photos, List<Pin> pins,
            boolean compared) {
        this(schemaVersion, eventId, occurredAt, rentalId, checkpointId, captureSetId, checkpointType, grade,
                ruleVersion, newDefectIds, unjudgeableFaces, photos, pins, compared, null);
    }

    /** compared를 안 주면 출고(OUT)는 false(비교 대상 없음), 그 밖에는 true. 1.2 계약의 '없을 때' 규칙과 같다. */
    public CheckpointGradedEvent(String schemaVersion, String eventId, OffsetDateTime occurredAt, String rentalId,
            String checkpointId, String captureSetId, CheckpointKind checkpointType, Grade grade, String ruleVersion,
            List<String> newDefectIds, List<Face> unjudgeableFaces, List<PhotoHash> photos, List<Pin> pins) {
        this(schemaVersion, eventId, occurredAt, rentalId, checkpointId, captureSetId, checkpointType, grade,
                ruleVersion, newDefectIds, unjudgeableFaces, photos, pins, checkpointType != CheckpointKind.OUT);
    }

    /** 1.3: 사람 검수 기록. confirmed·dismissed는 후보 수. */
    public record Review(String reviewedBy, OffsetDateTime completedAt, int confirmed, int dismissed) {
    }

    public record PhotoHash(String photoId, String sha256) {
    }

    /** 1.1: 리포트 3D 핀. 같은 흠집 묶음 하나에 하나, 표준 좌표 px 중심. isNew는 newDefectIds에 있으면 true. */
    public record Pin(String defectId, Face face, double x, double y, boolean isNew) {
    }
}
