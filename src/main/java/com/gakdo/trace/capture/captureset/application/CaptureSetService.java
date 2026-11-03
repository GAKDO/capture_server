package com.gakdo.trace.capture.captureset.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.capture.captureset.application.CaptureSetResults.CaptureSetCreateResponse;
import com.gakdo.trace.capture.captureset.application.CaptureSetResults.CaptureSetView;
import com.gakdo.trace.capture.captureset.application.CaptureSetResults.UploadReissueResponse;
import com.gakdo.trace.capture.captureset.application.CaptureSetResults.UploadTarget;
import com.gakdo.trace.capture.captureset.application.CaptureUploadedEvent.EventPhoto;
import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.DeviceModel;
import com.gakdo.trace.capture.captureset.domain.CompletionCheck;
import com.gakdo.trace.capture.captureset.domain.CompletionCheck.PhotoState;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.DomainException.FieldError;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.common.infra.Times;

/**
 * 촬영 세트 생성·완료·조회. 범위(부스·시점·렌탈)는 gateway가 보지 못하므로 여기서 토큰으로 다시 확인한다(decisions.md 11/2).
 */
@Service
public class CaptureSetService {

    private static final Logger log = LoggerFactory.getLogger(CaptureSetService.class);

    /** 찾지 못한 대상 이름(404 문장). */
    private static final String CAPTURE_SET = "촬영 세트";

    private final CaptureSetRepository sets;
    private final PhotoStorage storage;
    private final CaptureEventPublisher events;
    private final ObjectMapper json;
    private final Duration uploadTtl;
    private final ApplicationEventPublisher local;

    public CaptureSetService(CaptureSetRepository sets, PhotoStorage storage, CaptureEventPublisher events,
            ObjectMapper json, @Value("${app.storage.upload-ttl:10m}") Duration uploadTtl,
            ApplicationEventPublisher local) {
        this.local = local;
        this.sets = sets;
        this.storage = storage;
        this.events = events;
        this.json = json;
        this.uploadTtl = uploadTtl;
    }

    /** 새 사진의 사전 서명 업로드 정보. contentType은 image/jpeg·image/png. */
    public record NewPhoto(PhotoSpec spec, String contentType) {
    }

    /** 세트 안의 사진 한 장을 가리키는 (face, light, camera). */
    public record PhotoRef(Face face, Light light, Camera camera) {
    }

    @Transactional
    public CaptureSetCreateResponse create(Caller caller, String rentalId, String checkpointId, CaptureSource source,
            DeviceModel device, List<NewPhoto> photos) {
        return doCreate(caller, rentalId, checkpointId, source, device, null, photos);
    }

    /** profile은 세트 범위(null이면 FULL, QUICK은 부스 빠른 검사). */
    @Transactional
    public CaptureSetCreateResponse create(Caller caller, String rentalId, String checkpointId, CaptureSource source,
            DeviceModel device, CaptureProfile profile, List<NewPhoto> photos) {
        return doCreate(caller, rentalId, checkpointId, source, device, profile, photos);
    }

    /** 두 create의 본문. 트랜잭션은 부른 public 메서드의 것을 쓴다(this로 @Transactional 메서드를 부르지 않기 위해 뺌). */
    private CaptureSetCreateResponse doCreate(Caller caller, String rentalId, String checkpointId,
            CaptureSource source, DeviceModel device, CaptureProfile profile, List<NewPhoto> photos) {
        caller.requireCapturer();
        String boothId = null;
        if (caller.isBooth()) {
            if (source != CaptureSource.BOOTH) {
                throw Caller.outOfScope();
            }
            boothId = caller.scope();
        } else if (source != CaptureSource.PHONE || !checkpointId.equals(caller.scope())
                || !rentalId.equals(caller.rentalId())) {
            throw Caller.outOfScope();
        }

        CaptureSet set = CaptureSet.open(rentalId, checkpointId, source, boothId, device, profile,
                photos.stream().map(NewPhoto::spec).toList());
        sets.save(set);

        List<UploadTarget> uploads = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            uploads.add(uploadTarget(set.getPhotos().get(i), photos.get(i).contentType()));
        }
        log.info("촬영 세트 생성 set={} checkpoint={} source={} profile={} photos={}", set.getPublicId(), checkpointId,
                source, set.getProfile(), photos.size());
        return new CaptureSetCreateResponse(set.getPublicId(), uploads);
    }

    /**
     * 업로드 URL 다시 받기(contracts reissueUploadUrls, 11/2 결정). 권한·범위는 세트 생성과 같고, 저장소에 이미 있는 사진은 빼고
     * 새 사전 서명 PUT URL을 준다. requested가 null이면 세트의 사진 전부가 대상이다. 세트는 바꾸지 않는다.
     */
    @Transactional(readOnly = true)
    public UploadReissueResponse reissueUploads(Caller caller, String setId, List<PhotoRef> requested) {
        caller.requireCapturer();
        CaptureSet set = sets.findByPublicId(setId).orElseThrow(() -> DomainException.notFound(CAPTURE_SET));
        checkWriteScope(caller, set);
        if (set.isCompleted()) {
            throw new DomainException(ErrorCode.CAPTURE_SET_CLOSED, null);
        }

        List<Photo> targets = requested == null ? set.getPhotos() : pick(set, requested);
        List<UploadTarget> uploads = new ArrayList<>();
        for (Photo photo : targets) {
            if (!existsQuietly(photo)) {
                uploads.add(uploadTarget(photo, contentTypeOf(photo)));
            }
        }
        log.info("업로드 URL 재발급 set={} requested={} issued={}", set.getPublicId(),
                requested == null ? "all" : requested.size(), uploads.size());
        return new UploadReissueResponse(uploads);
    }

    /** 요청한 사진을 세트 순서대로 고른다. 세트에 없는 (face, light, camera)는 photos[i]로 400. */
    private static List<Photo> pick(CaptureSet set, List<PhotoRef> requested) {
        Set<PhotoRef> wanted = new LinkedHashSet<>(requested);
        Set<PhotoRef> inSet = new HashSet<>();
        for (Photo p : set.getPhotos()) {
            inSet.add(new PhotoRef(p.getFace(), p.getLight(), p.getCamera()));
        }
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            if (!inSet.contains(requested.get(i))) {
                errors.add(new FieldError("photos[" + i + "]", "세트에 없는 사진입니다(face, light, camera)."));
            }
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "세트에 없는 사진이 있습니다.", errors);
        }
        return set.getPhotos().stream()
                .filter(p -> wanted.contains(new PhotoRef(p.getFace(), p.getLight(), p.getCamera())))
                .toList();
    }

    private UploadTarget uploadTarget(Photo photo, String contentType) {
        PhotoStorage.UploadUrl url = storage.presignPut(photo.getObjectKey(), contentType, photo.getSha256(),
                uploadTtl);
        return new UploadTarget(photo.getFace(), photo.getLight(), photo.getCamera(), "PUT", url.url(), url.headers(),
                url.expiresAt());
    }

    /** 세트 생성 때 알린 형식. 객체 키 확장자(jpg·png)에 남아 있다. */
    private static String contentTypeOf(Photo photo) {
        return photo.getObjectKey().endsWith(".png") ? "image/png" : "image/jpeg";
    }

    /**
     * 완료: 잠금 → 멱등 키 → 필수 면 skip 검사 → 저장소 해시 확인 → 필수 면 → 상태·이벤트. 실패하면 아무것도 바뀌지 않는다.
     */
    @Transactional
    public CaptureSetView complete(Caller caller, String setId, String idempotencyKey, Map<Face, String> skipped) {
        caller.requireCapturer();
        CaptureSet set = sets.findForUpdate(setId).orElseThrow(() -> DomainException.notFound(CAPTURE_SET));
        checkWriteScope(caller, set);

        String requestHash = requestHash(skipped);
        if (set.getCompletionKey() != null) {
            if (!set.getCompletionKey().equals(idempotencyKey)) {
                throw new DomainException(ErrorCode.CAPTURE_SET_CLOSED, null);
            }
            if (!set.getCompletionRequestSha256().equals(requestHash)) {
                throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, null);
            }
            return json.convertValue(set.getCompletionResponse(), CaptureSetView.class);
        }
        if (set.isCompleted()) {
            throw new DomainException(ErrorCode.CAPTURE_SET_CLOSED, null);
        }

        CompletionCheck.checkSkippable(set.getSource(), set.getProfile(), skipped);
        List<PhotoState> states = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (Photo photo : set.getPhotos()) {
            String actual = storage.sha256Of(photo.getObjectKey()).orElse(null);
            states.add(new PhotoState(photo.getFace(), photo.getLight(), photo.getCamera(), photo.getSha256(), actual));
            if (actual != null) {
                present.add(photo.getPublicId());
            }
        }
        CompletionCheck.check(set.getSource(), set.getProfile(), states, skipped);

        set.complete(present, skipped, idempotencyKey, requestHash);
        CaptureSetView view = CaptureSetView.of(set, Photo::isUploaded);
        set.recordCompletionResponse(json.convertValue(view, new TypeReference<Map<String, Object>>() { }));
        events.publishUploaded(event(set));
        if (set.isQuick()) {
            // 빠른 검사는 정합을 기다리지 않는다. 탐지가 먼저 와 있었으면 같은 트랜잭션에서 바로 등급을 매긴다(inspection이 듣는다)
            local.publishEvent(new QuickSetCompleted(set.getPublicId()));
        }
        log.info("촬영 세트 완료 set={} checkpoint={} uploaded={} skipped={}", set.getPublicId(),
                set.getCheckpointId(), present.size(), skipped.keySet());
        return view;
    }

    /** 조회: ADMIN은 모두, 촬영 JWT는 자기 시점 세트만. UPLOADING이면 저장소에 올라갔는지 보여 준다(해시 확인은 완료 때). */
    @Transactional(readOnly = true)
    public CaptureSetView get(Caller caller, String setId) {
        if (!caller.isAdmin() && !caller.isCaptureToken()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "촬영 링크로 받은 토큰이나 관리자만 볼 수 있습니다.");
        }
        CaptureSet set = sets.findByPublicId(setId).orElseThrow(() -> DomainException.notFound(CAPTURE_SET));
        if (!caller.isAdmin() && !set.getCheckpointId().equals(caller.scope())) {
            throw Caller.outOfScope();
        }
        if (set.getStatus() != SetStatus.UPLOADING) {
            return CaptureSetView.of(set, Photo::isUploaded);
        }
        return CaptureSetView.of(set, this::existsQuietly);
    }

    private boolean existsQuietly(Photo photo) {
        try {
            return storage.exists(photo.getObjectKey());
        } catch (RuntimeException e) {
            log.warn("저장소 확인 실패 key={}: {}", photo.getObjectKey(), e.toString());
            return false;
        }
    }

    private static void checkWriteScope(Caller caller, CaptureSet set) {
        boolean allowed = caller.isBooth()
                ? set.getSource() == CaptureSource.BOOTH && caller.scope().equals(set.getBoothId())
                : set.getSource() == CaptureSource.PHONE && set.getCheckpointId().equals(caller.scope())
                        && set.getRentalId().equals(caller.rentalId());
        if (!allowed) {
            throw Caller.outOfScope();
        }
    }

    private static CaptureUploadedEvent event(CaptureSet set) {
        List<EventPhoto> photos = set.getPhotos().stream()
                .filter(Photo::isUploaded)
                .filter(p -> !set.getSkippedFaces().containsKey(p.getFace()))
                .map(p -> new EventPhoto(p.getPublicId(), p.getFace(), p.getLight(), p.getCamera(), p.getObjectKey(),
                        p.getSha256()))
                .toList();
        return new CaptureUploadedEvent(CaptureUploadedEvent.SCHEMA_VERSION, PublicIds.next(), Times.now(),
                set.getRentalId(), set.getCheckpointId(), set.getPublicId(), set.getSource(),
                set.getSource() == CaptureSource.PHONE, photos, set.getDevice(), Map.copyOf(set.getSkippedFaces()));
    }

    /** 같은 키에 같은 본문인지 가리는 해시. 키 순서와 상관없이 같은 값이 나오게 정렬한다. */
    private String requestHash(Map<Face, String> skipped) {
        try {
            byte[] canonical = json.writeValueAsString(new TreeMap<>(skipped)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
