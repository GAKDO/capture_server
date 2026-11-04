package com.gakdo.trace.capture.inspection.domain;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.common.infra.Times;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 시점의 등급(시점당 하나). 등급을 매긴 세트와 그 세트의 판정 불가 면을 함께 둬 다음 시점이 이 결과와 비교한다.
 * 같은 시점에 세트가 다시 등급을 받으면(재촬영) 이 행을 새 세트로 바꾼다.
 */
@Entity
@Table(name = "grade_result", schema = "inspection")
public class GradeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checkpoint_id", nullable = false, updatable = false, length = 21)
    private String checkpointId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 1, columnDefinition = "char(1)")
    private Grade grade;

    @Column(name = "rule_version", nullable = false, length = 32)
    private String ruleVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "capture_set_id")
    private CaptureSet captureSet;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unjudgeable_faces", nullable = false, columnDefinition = "jsonb")
    private List<Face> unjudgeableFaces;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected GradeResult() {
    }

    public GradeResult(String checkpointId) {
        this.checkpointId = checkpointId;
        this.createdAt = Times.now();
    }

    public void recordGrade(CaptureSet set, Grade grade, String ruleVersion, List<String> reasons, List<Face> unjudgeable) {
        this.captureSet = set;
        this.grade = grade;
        this.ruleVersion = ruleVersion;
        this.reasons = List.copyOf(reasons);
        this.unjudgeableFaces = List.copyOf(unjudgeable);
        this.updatedAt = Times.now();
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public Grade getGrade() {
        return grade;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public CaptureSet getCaptureSet() {
        return captureSet;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<Face> getUnjudgeableFaces() {
        return unjudgeableFaces;
    }
}
