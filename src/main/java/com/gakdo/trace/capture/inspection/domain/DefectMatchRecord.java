package com.gakdo.trace.capture.inspection.domain;

import java.time.OffsetDateTime;

import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.comparison.domain.MatchStatus;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** 흠집 하나의 시점 비교 판정(inspection.defect_match). 규칙 버전(view-match-v1)을 함께 남긴다(FR-INS-03). */
@Entity
@Table(name = "defect_match", schema = "inspection")
public class DefectMatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "defect_id", nullable = false, updatable = false)
    private Defect defect;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prev_defect_id", updatable = false)
    private Defect prevDefect;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private MatchStatus status;

    @Column(name = "distance_mm", updatable = false)
    private Float distanceMm;

    @Column(name = "rule_version", nullable = false, updatable = false, length = 32)
    private String ruleVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DefectMatchRecord() {
    }

    public DefectMatchRecord(Defect defect, Defect prevDefect, MatchStatus status, Double distanceMm,
            String ruleVersion) {
        this.defect = defect;
        this.prevDefect = prevDefect;
        this.status = status;
        this.distanceMm = distanceMm == null ? null : distanceMm.floatValue();
        this.ruleVersion = ruleVersion;
        this.createdAt = Times.now();
        this.updatedAt = createdAt;
    }

    public Defect getDefect() {
        return defect;
    }

    public Defect getPrevDefect() {
        return prevDefect;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public Double getDistanceMm() {
        return distanceMm == null ? null : Double.parseDouble(distanceMm.toString());
    }

    public String getRuleVersion() {
        return ruleVersion;
    }
}
