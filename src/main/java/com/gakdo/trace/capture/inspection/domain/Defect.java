package com.gakdo.trace.capture.inspection.domain;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.comparison.domain.DefectClass;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * 탐지한 흠집 하나(사진 한 장 기준). photoPolygon은 탐지한 그 사진의 원본 px이고, polygon은 표준 좌표 px(정합 뒤 채움, 1 px = 0.25 mm).
 * 정합하지 못한 사진의 흠집은 polygon이 null이며 비교·등급·조회에서 빠진다(헌법 III).
 */
@Entity
@Table(name = "defect", schema = "inspection")
public class Defect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 21)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "photo_id", nullable = false, updatable = false)
    private Photo photo;

    @Convert(converter = DefectClassConverter.class)
    @Column(name = "class", nullable = false, updatable = false, length = 8)
    private DefectClass defectClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "photo_polygon", nullable = false, columnDefinition = "jsonb")
    private List<List<Double>> photoPolygon;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<List<Double>> polygon;

    @Column(name = "length_mm")
    private Float lengthMm;

    @Column(name = "area_mm2")
    private Float areaMm2;

    @Column(nullable = false, updatable = false)
    private Float confidence;

    @Column(name = "model_version", nullable = false, updatable = false, length = 64)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private DefectSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Defect() {
    }

    private Defect(String publicId, Photo photo, DefectClass defectClass, List<List<Double>> photoPolygon,
            double confidence, String modelVersion) {
        this.publicId = publicId;
        this.photo = photo;
        this.defectClass = defectClass;
        this.photoPolygon = List.copyOf(photoPolygon);
        this.confidence = (float) confidence;
        this.modelVersion = modelVersion;
        this.source = DefectSource.AUTO;
        this.createdAt = Times.now();
        this.updatedAt = createdAt;
    }

    /** 부스(Jetson) 탐지: 원본 px만 있고 표준 좌표는 정합 뒤 {@link #place}. */
    public static Defect fromBooth(Photo photo, DefectClass defectClass, List<List<Double>> photoPolygon,
            double confidence, String modelVersion) {
        return new Defect(PublicIds.next(), photo, defectClass, photoPolygon, confidence, modelVersion);
    }

    /** inspect-worker 탐지(폰): 표준 좌표가 먼저 있고, 원본 px는 호모그래피 역행렬로 구한다. ID는 worker가 준 값. */
    public static Defect fromWorker(String defectId, Photo photo, DefectClass defectClass,
            List<List<Double>> photoPolygon, StandardShape standard, double confidence, String modelVersion) {
        Defect d = new Defect(defectId, photo, defectClass, photoPolygon, confidence, modelVersion);
        List<List<Double>> polygon = standard.polygon();
        d.polygon = List.copyOf(polygon);
        d.lengthMm = toFloat(standard.lengthMm() != null ? standard.lengthMm() : PolygonMeasure.lengthMm(polygon));
        d.areaMm2 = toFloat(standard.areaMm2() != null ? standard.areaMm2() : PolygonMeasure.areaMm2(polygon));
        return d;
    }

    /** worker가 준 표준 좌표 polygon과 길이·넓이(null이면 polygon으로 잰다). */
    public record StandardShape(List<List<Double>> polygon, Double lengthMm, Double areaMm2) {
    }

    /** 사진의 호모그래피로 원본 px를 표준 좌표 px로 옮기고 길이·넓이를 잰다. 옮길 수 없으면 표준 좌표 없음으로 둔다. */
    public void place(Homography photoToStandard) {
        try {
            List<List<Double>> standard = photoToStandard.map(photoPolygon);
            this.polygon = standard;
            this.lengthMm = toFloat(PolygonMeasure.lengthMm(standard));
            this.areaMm2 = toFloat(PolygonMeasure.areaMm2(standard));
        } catch (IllegalArgumentException e) {
            this.polygon = null;
            this.lengthMm = null;
            this.areaMm2 = null;
        }
        this.updatedAt = Times.now();
    }

    /** real(float4) 값을 그 십진 표기 그대로 double로(10.05f → 10.05, 10.050000190… 아님). */
    private static Double widen(Float v) {
        return v == null ? null : Double.parseDouble(v.toString());
    }

    private static Float toFloat(Double v) {
        return v == null ? null : v.floatValue();
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Photo getPhoto() {
        return photo;
    }

    public DefectClass getDefectClass() {
        return defectClass;
    }

    public List<List<Double>> getPhotoPolygon() {
        return photoPolygon;
    }

    public List<List<Double>> getPolygon() {
        return polygon;
    }

    public Double getLengthMm() {
        return widen(lengthMm);
    }

    public Double getAreaMm2() {
        return widen(areaMm2);
    }

    public double getConfidence() {
        return confidence;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public DefectSource getSource() {
        return source;
    }
}
