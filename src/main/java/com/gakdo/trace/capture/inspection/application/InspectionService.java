package com.gakdo.trace.capture.inspection.application;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.InspectEvent.Alignment;
import com.gakdo.trace.capture.inspection.application.InspectEvent.WorkerDefect;
import com.gakdo.trace.capture.inspection.domain.AlignmentMethod;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.FaceAlignment;
import com.gakdo.trace.capture.inspection.domain.FaceTexture;
import com.gakdo.trace.capture.inspection.domain.Homography;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.FaceTextureRepository;

/**
 * 정합·탐지 결과(trace.inspect.completed) 저장. 사진별 호모그래피를 남기고, 부스 세트는 저장된 탐지의 원본 px를 표준 좌표로 옮기며,
 * 폰 세트는 worker가 탐지한 흠집(표준 좌표)을 저장한다. 그 뒤 준비됐으면 등급을 매긴다.
 * 이미 처리한 세트에 같은 이벤트가 다시 오면 아무것도 바꾸지 않는다(멱등).
 */
@Service
public class InspectionService {

    private static final Logger log = LoggerFactory.getLogger(InspectionService.class);

    private final CaptureSetRepository sets;
    private final DefectRepository defects;
    private final FaceAlignmentRepository alignments;
    private final FaceTextureRepository textures;
    private final GradingService grading;

    public InspectionService(CaptureSetRepository sets, DefectRepository defects, FaceAlignmentRepository alignments,
            FaceTextureRepository textures, GradingService grading) {
        this.sets = sets;
        this.defects = defects;
        this.alignments = alignments;
        this.textures = textures;
        this.grading = grading;
    }

    /** @return 처리했으면 true, 이미 처리한 세트라 건너뛰었으면 false */
    @Transactional
    public boolean onInspected(InspectEvent event) {
        require(PublicIds.isValid(event.captureSetId()), "captureSetId가 올바르지 않습니다");
        require(event.modelVersion() != null && !event.modelVersion().isBlank()
                && event.modelVersion().length() <= 64, "modelVersion이 없습니다");
        require(event.alignments() != null && event.defects() != null, "alignments·defects는 필수입니다");
        CaptureSet set = sets.findForUpdate(event.captureSetId())
                .orElseThrow(() -> new InvalidEventException("없는 촬영 세트입니다: " + event.captureSetId()));
        require(set.getCheckpointId().equals(event.checkpointId()), "봉투 checkpointId가 세트와 다릅니다");
        if (set.getStatus() == SetStatus.INSPECTED || set.getStatus() == SetStatus.GRADED) {
            log.info("이미 검사 결과를 받은 세트라 건너뜁니다 set={} eventId={}", set.getPublicId(), event.eventId());
            return false;
        }
        require(set.getStatus() == SetStatus.UPLOADED, "완료되지 않은 세트입니다");

        Map<String, Photo> photos = new HashMap<>();
        set.getPhotos().forEach(p -> photos.put(p.getPublicId(), p));
        Map<Long, FaceAlignment> byPhoto = new HashMap<>();
        Map<String, Homography> homographies = new HashMap<>();
        for (Alignment a : event.alignments()) {
            Photo photo = photos.get(a.photoId());
            require(photo != null, "세트에 없는 사진의 정합 결과입니다: " + a.photoId());
            require(!homographies.containsKey(a.photoId()), "같은 사진의 정합 결과가 두 번 있습니다: " + a.photoId());
            require(a.rectifiedKey() != null && !a.rectifiedKey().isBlank(), "rectifiedKey가 없습니다");
            Homography h = homography(a);
            homographies.put(a.photoId(), h);
            FaceAlignment saved = new FaceAlignment(photo, method(a.method()), a.homography(), a.errorPx(),
                    a.rectifiedKey());
            byPhoto.put(photo.getId(), saved);
        }
        alignments.saveAll(byPhoto.values());
        recordFailures(set, event.failedPhotos(), photos, homographies);
        saveTextures(set, event.textureKeys());

        if (set.getSource() == CaptureSource.BOOTH) {
            if (!event.defects().isEmpty()) {
                log.warn("부스 세트인데 worker 탐지가 왔습니다. 부스 탐지만 씁니다 set={} 개수={}", set.getPublicId(),
                        event.defects().size());
            }
            List<Defect> booth = defects.findBySet(set);
            GradingService.place(booth, byPhoto);
        } else {
            defects.saveAll(event.defects().stream()
                    .map(d -> workerDefect(d, photos, homographies, event.modelVersion()))
                    .filter(d -> d != null)
                    .toList());
        }
        set.markInspected();
        log.info("검사 결과 저장 set={} 정합={} 실패={} worker 탐지={}", set.getPublicId(), byPhoto.size(),
                event.failedPhotos() == null ? 0 : event.failedPhotos().size(), event.defects().size());
        grading.gradeIfReady(set);
        return true;
    }

    /**
     * 정합 실패 사유를 사진에 남기고 로그로도 남긴다(11/9: 옆면 판정 불가 원인을 서버에서 바로 보려고). 세트에 없는 사진·정합 결과가 있는
     * 사진의 실패 항목은 무시한다(worker 버그로 이벤트 전체를 버리지 않게).
     */
    private static void recordFailures(CaptureSet set, List<InspectEvent.FailedPhoto> failed, Map<String, Photo> photos,
            Map<String, Homography> homographies) {
        if (failed == null || failed.isEmpty()) {
            return;
        }
        OffsetDateTime now = Times.now();
        for (InspectEvent.FailedPhoto f : failed) {
            if (f == null || f.photoId() == null) {
                continue;
            }
            Photo photo = photos.get(f.photoId());
            if (photo == null || homographies.containsKey(f.photoId())) {
                log.warn("정합 실패 항목을 무시합니다 set={} photoId={}", set.getPublicId(), f.photoId());
                continue;
            }
            photo.markAlignFailed(f.reason(), now);
            log.warn("정합 실패 set={} 사진={}-{}-{} 사유={}", set.getPublicId(), photo.getFace(), photo.getLight(),
                    photo.getCamera(), photo.getAlignFailure());
        }
    }

    private Defect workerDefect(WorkerDefect d, Map<String, Photo> photos, Map<String, Homography> homographies,
            String modelVersion) {
        require(PublicIds.isValid(d.defectId()), "defectId가 올바르지 않습니다");
        Photo photo = photos.get(d.photoId());
        require(photo != null, "세트에 없는 사진의 흠집입니다: " + d.photoId());
        DefectClass cls = defectClass(d.defectClass());
        require(validPolygon(d.polygon()), "흠집 polygon은 [x, y] 꼭짓점 3개 이상입니다");
        require(d.confidence() != null && d.confidence() >= 0 && d.confidence() <= 1, "confidence는 0~1입니다");
        if (defects.existsByPublicId(d.defectId())) {
            log.warn("이미 저장된 흠집 ID라 건너뜁니다 defectId={}", d.defectId());
            return null;
        }
        Homography h = homographies.get(d.photoId());
        // 원본 px는 표준 좌표 → 사진 역변환으로 구한다. 정합 결과가 없는 사진이면 구할 수 없어 빈 목록으로 둔다
        List<List<Double>> photoPolygon = List.of();
        if (h != null) {
            try {
                photoPolygon = h.inverse().map(d.polygon());
            } catch (IllegalArgumentException e) {
                log.warn("원본 px를 구하지 못했습니다 defectId={}: {}", d.defectId(), e.getMessage());
            }
        } else {
            log.warn("정합 결과가 없는 사진의 흠집입니다 defectId={} photoId={}", d.defectId(), d.photoId());
        }
        return Defect.fromWorker(d.defectId(), photo, cls, photoPolygon,
                new Defect.StandardShape(d.polygon(), d.lengthMm(), d.areaMm2()), d.confidence(), modelVersion);
    }

    private void saveTextures(CaptureSet set, Map<String, String> keys) {
        if (keys == null) {
            return;
        }
        Set<Face> seen = new HashSet<>();
        keys.forEach((face, key) -> {
            Face f = face(face);
            require(key != null && !key.isBlank() && key.length() <= 512, "텍스처 키가 올바르지 않습니다");
            if (seen.add(f)) {
                textures.save(new FaceTexture(set, f, key));
            }
        });
    }

    static boolean validPolygon(List<List<Double>> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        for (List<Double> p : polygon) {
            if (p == null || p.size() != 2 || p.get(0) == null || p.get(1) == null || !Double.isFinite(p.get(0))
                    || !Double.isFinite(p.get(1))) {
                return false;
            }
        }
        return true;
    }

    private static Homography homography(Alignment a) {
        try {
            return Homography.of(a.homography());
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(e.getMessage() + ": " + a.photoId());
        }
    }

    private static AlignmentMethod method(String value) {
        try {
            return AlignmentMethod.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidEventException("정합 방법이 올바르지 않습니다: " + value);
        }
    }

    private static DefectClass defectClass(String code) {
        try {
            DefectClass c = DefectClass.fromCode(code);
            require(c.code().equals(code), "흠집 종류는 소문자 코드입니다: " + code);
            return c;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidEventException("흠집 종류가 올바르지 않습니다: " + code);
        }
    }

    private static Face face(String value) {
        try {
            return Face.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidEventException("면 코드가 올바르지 않습니다: " + value);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new InvalidEventException(message);
        }
    }
}
