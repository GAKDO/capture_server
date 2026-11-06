package com.gakdo.trace.capture.captureset.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.DomainException.FieldError;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.common.infra.Times;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * 한 시점의 촬영 세트. 렌탈·시점 ID는 rental-server의 publicId를 값으로만 든다(서비스 간 FK 없음).
 * 상태는 UPLOADING → UPLOADED(완료)로만 가고, 완료된 세트의 사진·건너뛴 면은 바뀌지 않는다(헌법 III).
 */
@Entity
@Table(name = "capture_set", schema = "capture")
public class CaptureSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 21)
    private String publicId;

    @Column(name = "rental_id", nullable = false, updatable = false, length = 21)
    private String rentalId;

    @Column(name = "checkpoint_id", nullable = false, updatable = false, length = 21)
    private String checkpointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private CaptureSource source;

    @Column(name = "booth_id", updatable = false, length = 32)
    private String boothId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private CaptureProfile profile = CaptureProfile.FULL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SetStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Quality quality;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skipped_faces", nullable = false, columnDefinition = "jsonb")
    private Map<Face, String> skippedFaces = new EnumMap<>(Face.class);

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "device", updatable = false, columnDefinition = "jsonb")
    private DeviceModel device;

    @Column(name = "completion_key", length = 128)
    private String completionKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "completion_request_sha256", length = 64, columnDefinition = "char(64)")
    private String completionRequestSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completion_response", columnDefinition = "jsonb")
    private Map<String, Object> completionResponse;

    @Column(name = "detections_received_at")
    private OffsetDateTime detectionsReceivedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "detection_model_version", length = 64)
    private String detectionModelVersion;

    @Column(name = "inference_ms")
    private Integer inferenceMs;

    @Column(name = "booth_total_ms")
    private Integer boothTotalMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "captureSet", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<Photo> photos = new ArrayList<>();

    protected CaptureSet() {
    }

    /** 사진으로 알릴 값. extension은 contentType에서 정한 jpg·png. */
    public record PhotoSpec(Face face, Light light, Camera camera, String extension, String sha256,
            OffsetDateTime takenAt) {
    }

    /**
     * 세트를 연다. 출처에 맞지 않는 카메라·조명과 같은 (면, 조명, 카메라) 겹침은 400(photos[i]).
     * 사진 순서는 요청 순서 그대로 저장한다(응답 uploads도 같은 순서).
     */
    public static CaptureSet open(String rentalId, String checkpointId, CaptureSource source, String boothId,
            List<PhotoSpec> specs) {
        return open(rentalId, checkpointId, source, boothId, null, specs);
    }

    /** device는 찍은 기기의 기종·치수. 없으면 null(inspect-worker가 기본 기종으로 펼친다). */
    public static CaptureSet open(String rentalId, String checkpointId, CaptureSource source, String boothId,
            DeviceModel device, List<PhotoSpec> specs) {
        return open(rentalId, checkpointId, source, boothId, device, CaptureProfile.FULL, specs);
    }

    /** profile은 세트 범위(null이면 FULL). QUICK은 부스만이고, 폰이면 400(profile). */
    public static CaptureSet open(String rentalId, String checkpointId, CaptureSource source, String boothId,
            DeviceModel device, CaptureProfile profile, List<PhotoSpec> specs) {
        CaptureProfile range = profile == null ? CaptureProfile.FULL : profile;
        List<FieldError> errors = new ArrayList<>();
        if (range == CaptureProfile.QUICK && source != CaptureSource.BOOTH) {
            errors.add(new FieldError("profile", "빠른 검사(QUICK)는 부스 촬영만 쓸 수 있습니다"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < specs.size(); i++) {
            PhotoSpec spec = specs.get(i);
            String field = "photos[" + i + "]";
            if (!FaceRules.allows(source, spec.camera())) {
                errors.add(new FieldError(field + ".camera", source + " 촬영에 쓸 수 없는 카메라입니다"));
            }
            if (!FaceRules.allows(source, spec.light())) {
                errors.add(new FieldError(field + ".light", source + " 촬영에 쓸 수 없는 조명입니다"));
            }
            if (!seen.add(spec.face() + "-" + spec.light() + "-" + spec.camera())) {
                errors.add(new FieldError(field, "같은 면·조명·카메라의 사진이 이미 있습니다"));
            }
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "사진 목록이 올바르지 않습니다.", errors);
        }

        CaptureSet set = new CaptureSet();
        set.publicId = PublicIds.next();
        set.rentalId = rentalId;
        set.checkpointId = checkpointId;
        set.source = source;
        set.boothId = boothId;
        set.profile = range;
        set.device = device;
        set.status = SetStatus.UPLOADING;
        set.quality = Quality.OK;
        set.createdAt = Times.now();
        set.updatedAt = set.createdAt;
        for (PhotoSpec spec : specs) {
            set.photos.add(new Photo(set, spec.face(), spec.light(), spec.camera(), spec.extension(), spec.sha256(),
                    spec.takenAt()));
        }
        return set;
    }

    public CaptureProfile getProfile() {
        return profile == null ? CaptureProfile.FULL : profile;
    }

    public boolean isQuick() {
        return getProfile() == CaptureProfile.QUICK;
    }

    /** 이미 완료됐으면(다른 키든 상태든) 다시 완료할 수 없다. */
    public boolean isCompleted() {
        return status != SetStatus.UPLOADING || completionKey != null;
    }

    /**
     * 완료. present는 저장소에서 확인된 사진의 publicId. 판정(CompletionCheck)을 통과한 뒤에만 부른다.
     */
    public void complete(Set<String> present, Map<Face, String> skipped, String key, String requestSha256) {
        if (isCompleted()) {
            throw new DomainException(ErrorCode.CAPTURE_SET_CLOSED, null);
        }
        OffsetDateTime now = Times.now();
        for (Photo photo : photos) {
            photo.markUploaded(present.contains(photo.getPublicId()), now);
        }
        this.skippedFaces = skipped.isEmpty() ? new EnumMap<>(Face.class) : new EnumMap<>(skipped);
        this.status = SetStatus.UPLOADED;
        this.completionKey = key;
        this.completionRequestSha256 = requestSha256;
        this.completedAt = now;
        this.updatedAt = now;
    }

    /** 부스 탐지를 받았다(CA2). 부스 세트는 정합과 탐지가 모두 와야 등급을 매긴다. */
    public void markDetectionsReceived() {
        markDetectionsReceived(null, null);
    }

    /** modelVersion·inferenceMs는 성능 그래프용(11/6). 다시 보내면 마지막 요청 값이 남는다(탐지를 통째로 바꾸는 것과 같다). */
    public void markDetectionsReceived(String modelVersion, Integer inferenceMs) {
        markDetectionsReceived(modelVersion, inferenceMs, null);
    }

    /** boothTotalMs는 부스 촬영 시작 버튼→판정 ms(선택, contracts #30). 다시 보내면 마지막 요청 값이 남는다. */
    public void markDetectionsReceived(String modelVersion, Integer inferenceMs, Integer boothTotalMs) {
        this.detectionsReceivedAt = Times.now();
        this.detectionModelVersion = modelVersion;
        this.inferenceMs = inferenceMs;
        this.boothTotalMs = boothTotalMs;
        this.updatedAt = detectionsReceivedAt;
    }

    /** 정합 결과를 받았다(CA2): UPLOADED → INSPECTED. */
    public void markInspected() {
        if (status != SetStatus.UPLOADED) {
            throw new IllegalStateException("완료된 세트만 검사 결과를 받는다: " + status);
        }
        this.status = SetStatus.INSPECTED;
        this.updatedAt = Times.now();
    }

    /** 판정할 수 있는 면이 하나도 없다(CA2). 등급 없이 INSPECTED에 머물고 다시 찍어야 한다. */
    public void markUnjudgeable() {
        this.quality = Quality.UNJUDGEABLE;
        this.updatedAt = Times.now();
    }

    /** 빠른 검사 세트를 정합 없이 등급 매겼다(11/3 MVP): UPLOADED → GRADED. 이후 부스 탐지는 409. */
    public void markGradedQuick() {
        if (profile != CaptureProfile.QUICK || status != SetStatus.UPLOADED) {
            throw new IllegalStateException("완료된 빠른 검사 세트만 정합 없이 등급을 매긴다: " + profile + "/" + status);
        }
        this.status = SetStatus.GRADED;
        this.quality = Quality.OK;
        this.updatedAt = Times.now();
    }

    /** 등급을 매겼다(CA2): INSPECTED → GRADED. 이후 부스 탐지는 409. */
    public void markGraded() {
        if (status != SetStatus.INSPECTED) {
            throw new IllegalStateException("검사 결과를 받은 세트만 등급을 매긴다: " + status);
        }
        this.status = SetStatus.GRADED;
        this.quality = Quality.OK;
        this.updatedAt = Times.now();
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getDetectionModelVersion() {
        return detectionModelVersion;
    }

    public Integer getInferenceMs() {
        return inferenceMs;
    }

    public Integer getBoothTotalMs() {
        return boothTotalMs;
    }

    public OffsetDateTime getDetectionsReceivedAt() {
        return detectionsReceivedAt;
    }

    public Long getId() {
        return id;
    }

    /** 같은 Idempotency-Key로 다시 오면 돌려줄 첫 응답(JSON 객체). */
    public void recordCompletionResponse(Map<String, Object> response) {
        this.completionResponse = response;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getRentalId() {
        return rentalId;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public CaptureSource getSource() {
        return source;
    }

    public String getBoothId() {
        return boothId;
    }

    public SetStatus getStatus() {
        return status;
    }

    public Quality getQuality() {
        return quality;
    }

    public Map<Face, String> getSkippedFaces() {
        return skippedFaces;
    }

    public String getCompletionKey() {
        return completionKey;
    }

    public String getCompletionRequestSha256() {
        return completionRequestSha256;
    }

    public Map<String, Object> getCompletionResponse() {
        return completionResponse;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<Photo> getPhotos() {
        return photos;
    }

    public DeviceModel getDevice() {
        return device;
    }
}
