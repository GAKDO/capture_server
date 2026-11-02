package com.gakdo.trace.capture.inspection.domain;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gakdo.trace.capture.captureset.domain.Photo;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** 사진 한 장의 정합 결과(trace.inspect.completed alignments[]). 사진 px → 표준 좌표 px 호모그래피. */
@Entity
@Table(name = "face_alignment", schema = "inspection")
public class FaceAlignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "photo_id", nullable = false, updatable = false)
    private Photo photo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private AlignmentMethod method;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<List<Double>> homography;

    @Column(name = "error_px", updatable = false)
    private Float errorPx;

    @Column(name = "rectified_key", nullable = false, updatable = false, length = 512)
    private String rectifiedKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected FaceAlignment() {
    }

    public FaceAlignment(Photo photo, AlignmentMethod method, List<List<Double>> homography, Double errorPx,
            String rectifiedKey) {
        this.photo = photo;
        this.method = method;
        this.homography = homography.stream().map(List::copyOf).toList();
        this.errorPx = errorPx == null ? null : errorPx.floatValue();
        this.rectifiedKey = rectifiedKey;
        this.createdAt = Times.now();
        this.updatedAt = createdAt;
    }

    public Photo getPhoto() {
        return photo;
    }

    public Homography toHomography() {
        return Homography.of(homography);
    }

    public String getRectifiedKey() {
        return rectifiedKey;
    }
}
