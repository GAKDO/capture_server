package com.gakdo.trace.capture.captureset.domain;

import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gakdo.trace.capture.common.infra.PublicIds;
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

/** 세트 안의 사진 한 장. 본문은 MinIO에만 있고 여기에는 코드·키·해시만 둔다(원본은 덮어쓰지 않는다). */
@Entity
@Table(name = "photo", schema = "capture")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 21)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capture_set_id", nullable = false, updatable = false)
    private CaptureSet captureSet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 4)
    private Face face;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 4)
    private Light light;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private Camera camera;

    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Column(nullable = false)
    private boolean uploaded;

    @Column(name = "quality_ok", nullable = false)
    private boolean qualityOk = true;

    /** worker가 보낸 정합 실패 사유(없으면 null). 판정 불가 면 사유 문장에 붙인다(11/9). */
    @Column(name = "align_failure", length = 500)
    private String alignFailure;

    @Column(name = "taken_at", nullable = false, updatable = false)
    private OffsetDateTime takenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Photo() {
    }

    Photo(CaptureSet captureSet, Face face, Light light, Camera camera, String extension, String sha256,
            OffsetDateTime takenAt) {
        this.publicId = PublicIds.next();
        this.captureSet = captureSet;
        this.face = face;
        this.light = light;
        this.camera = camera;
        this.objectKey = objectKey(captureSet.getPublicId(), face, light, camera, extension);
        this.sha256 = sha256;
        this.takenAt = Times.utc(takenAt);
        this.createdAt = Times.now();
        this.updatedAt = createdAt;
    }

    /** 버킷 trace-captures 안의 키. 세트마다 다른 접두사라 다른 세트의 사진을 덮어쓰지 않는다. */
    public static String objectKey(String setId, Face face, Light light, Camera camera, String extension) {
        return "captures/" + setId + "/" + face + "-" + light + "-" + camera + "." + extension;
    }

    void markUploaded(boolean value, OffsetDateTime now) {
        this.uploaded = value;
        this.updatedAt = now;
    }

    /** 정합 실패 사유를 남긴다. 빈 사유는 "사유 없음"으로, 500자를 넘으면 자른다. */
    public void markAlignFailed(String reason, OffsetDateTime now) {
        String text = reason == null || reason.isBlank() ? "사유 없음" : reason.strip();
        this.alignFailure = text.length() > 500 ? text.substring(0, 500) : text;
        this.updatedAt = now;
    }

    public String getAlignFailure() {
        return alignFailure;
    }

    public Long getId() {
        return id;
    }

    public CaptureSet getCaptureSet() {
        return captureSet;
    }

    public String getPublicId() {
        return publicId;
    }

    public Face getFace() {
        return face;
    }

    public Light getLight() {
        return light;
    }

    public Camera getCamera() {
        return camera;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    public boolean isQualityOk() {
        return qualityOk;
    }
}
