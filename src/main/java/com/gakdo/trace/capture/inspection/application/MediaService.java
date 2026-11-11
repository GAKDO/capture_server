package com.gakdo.trace.capture.inspection.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.application.PhotoStorage;
import com.gakdo.trace.capture.captureset.application.PhotoStorage.ViewUrl;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.inspection.domain.FaceTexture;
import com.gakdo.trace.capture.inspection.infra.FaceTextureRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/**
 * 3D 트윈 텍스처·흠집 크롭 보기 URL(contracts getCheckpointMedia, FR-RPT-01). 검사 결과 조회와 같은 세트·같은 범위를 쓴다.
 * 텍스처는 inspect-worker가 trace-derived에 올린 면별 이미지(textureKeys)다. 크롭은 아직 만드는 곳이 없어 빈 목록이다.
 */
@Service
public class MediaService {

    private final CaptureSetRepository sets;
    private final GradeResultRepository grades;
    private final FaceTextureRepository textures;
    private final PhotoStorage storage;
    private final Duration ttl;

    public MediaService(CaptureSetRepository sets, GradeResultRepository grades, FaceTextureRepository textures,
            PhotoStorage storage, @Value("${app.storage.view-ttl:10m}") Duration ttl) {
        this.sets = sets;
        this.grades = grades;
        this.textures = textures;
        this.storage = storage;
        this.ttl = ttl;
    }

    public record MediaView(String checkpointId, OffsetDateTime expiresAt, List<Texture> textures, List<Crop> crops,
            List<PhotoView> photos) {
    }

    /** 원본 사진 보기 URL(계약 선택 필드 photos, 11/3). 빠른 검사 세트는 텍스처가 없어 관리자 화면이 이 사진 위에 흠집을 겹친다. */
    public record PhotoView(String face, String light, String camera, String url) {
    }

    public record Texture(String face, String url) {
    }

    public record Crop(String defectId, String url) {
    }

    @Transactional(readOnly = true)
    public MediaView get(Caller caller, String checkpointId) {
        CaptureSet set = InspectionQueryService.visibleSet(caller, checkpointId, grades, sets);
        OffsetDateTime expiresAt = Times.now().plus(ttl);
        List<Texture> list = new ArrayList<>();
        for (FaceTexture t : textures.findByCaptureSet(set)) {
            ViewUrl url = storage.presignDerivedGet(t.getObjectKey(), ttl);
            list.add(new Texture(t.getFace().name(), url.url()));
            if (url.expiresAt() != null && url.expiresAt().isBefore(expiresAt)) {
                expiresAt = url.expiresAt();
            }
        }
        List<PhotoView> photos = new ArrayList<>();
        for (Photo p : set.getPhotos()) {
            if (!p.isUploaded()) {
                continue;
            }
            ViewUrl url = storage.presignCaptureGet(p.getObjectKey(), ttl);
            photos.add(new PhotoView(p.getFace().name(), p.getLight().name(), p.getCamera().name(), url.url()));
            if (url.expiresAt() != null && url.expiresAt().isBefore(expiresAt)) {
                expiresAt = url.expiresAt();
            }
        }
        return new MediaView(checkpointId, expiresAt, list, List.of(), photos);
    }
}
