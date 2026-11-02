package com.gakdo.trace.capture.inspection.domain;

import java.time.OffsetDateTime;

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

/** 면별 텍스처 이미지 키(버킷 trace-derived, trace.inspect.completed textureKeys). */
@Entity
@Table(name = "face_texture", schema = "inspection")
public class FaceTexture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capture_set_id", nullable = false, updatable = false)
    private CaptureSet captureSet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 4)
    private Face face;

    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected FaceTexture() {
    }

    public FaceTexture(CaptureSet captureSet, Face face, String objectKey) {
        this.captureSet = captureSet;
        this.face = face;
        this.objectKey = objectKey;
        this.createdAt = Times.now();
        this.updatedAt = createdAt;
    }

    public Face getFace() {
        return face;
    }

    public String getObjectKey() {
        return objectKey;
    }
}
