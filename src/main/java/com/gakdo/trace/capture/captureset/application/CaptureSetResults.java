package com.gakdo.trace.capture.captureset.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.Quality;
import com.gakdo.trace.capture.captureset.domain.SetStatus;

/** 촬영 응답 스키마(contracts CaptureSetCreateResponse·UploadTarget·CaptureSet·CaptureSetPhoto). 엔티티를 그대로 내보내지 않는다. */
public final class CaptureSetResults {

    private CaptureSetResults() {
    }

    public record UploadTarget(Face face, Light light, Camera camera, String method, String url,
            Map<String, String> headers, OffsetDateTime expiresAt) {
    }

    public record CaptureSetCreateResponse(String captureSetId, List<UploadTarget> uploads) {
    }

    /** 업로드 URL 다시 받기 응답(contracts UploadReissueResponse). 모두 올라갔으면 빈 배열. */
    public record UploadReissueResponse(List<UploadTarget> uploads) {
    }

    public record CaptureSetPhotoView(Face face, Light light, Camera camera, String sha256, boolean uploaded,
            boolean qualityOk) {
    }

    public record CaptureSetView(String captureSetId, String checkpointId, CaptureSource source,
            CaptureProfile profile, String boothId, SetStatus status, Quality quality, List<CaptureSetPhotoView> photos) {

        static CaptureSetView of(CaptureSet set, java.util.function.Predicate<Photo> uploaded) {
            List<CaptureSetPhotoView> photos = set.getPhotos().stream()
                    .map(p -> new CaptureSetPhotoView(p.getFace(), p.getLight(), p.getCamera(), p.getSha256(),
                            uploaded.test(p), p.isQualityOk()))
                    .toList();
            return new CaptureSetView(set.getPublicId(), set.getCheckpointId(), set.getSource(), set.getProfile(),
                    set.getBoothId(),
                    set.getStatus(), set.getQuality(), photos);
        }
    }
}
