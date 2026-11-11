package com.gakdo.trace.capture.inspection.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gakdo.trace.capture.captureset.application.PhotoStorage;
import com.gakdo.trace.capture.captureset.application.PhotoStorage.ViewUrl;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DefectGroups;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/**
 * 공개 보유 기기 목록·상세의 흠집(contracts listCatalogInspections·getCatalogInspection, 11/8 민서 요청).
 * 로그인 없이 열리므로 공개 범위를 좁힌다: 부스(BOOTH)에서 찍고 등급이 난 세트만, 사람이 확정(CONFIRMED)한 흠집만,
 * 완료 때 올라간 사진·건너뛰지 않은 면만. 폰 촬영 세트는 고객의 방이 찍힐 수 있어 내보내지 않는다.
 * 흠집 수는 등급 계산과 같은 묶기(GradingService.quickGroups)로 센다: 조명·카메라만 다른 같은 흠집은 하나다.
 */
@Service
public class CatalogQueryService {

    private final GradeResultRepository grades;
    private final DefectRepository defects;
    private final PhotoStorage storage;
    private final Duration ttl;

    public CatalogQueryService(GradeResultRepository grades, DefectRepository defects, PhotoStorage storage,
            @Value("${app.storage.view-ttl:10m}") Duration ttl) {
        this.grades = grades;
        this.defects = defects;
        this.storage = storage;
        this.ttl = ttl;
    }

    public record Summary(String checkpointId, String grade, OffsetDateTime gradedAt, int defectCount,
            Map<String, Integer> defectsByClass) {
    }

    public record Detail(String checkpointId, String grade, OffsetDateTime gradedAt, int defectCount,
            Map<String, Integer> defectsByClass, OffsetDateTime expiresAt, List<PhotoView> photos,
            List<PhotoDefectView> photoDefects) {
    }

    public record PhotoView(String face, String light, String camera, String url) {
    }

    public record PhotoDefectView(String defectId, String face, String light, String camera,
            @JsonProperty("class") String defectClass, List<List<Double>> polygon, String groupId) {
    }

    /** 공개 범위 안의 세트와 그 세트의 확정 흠집 묶음. */
    record Visible(GradeResult grade, CaptureSet set, List<Defect> confirmed, List<DefectGroups.Group> groups) {

        Summary summary() {
            Map<String, Integer> byClass = new LinkedHashMap<>();
            for (DefectClass c : DefectClass.values()) {
                byClass.put(c.code(), 0);
            }
            groups.forEach(g -> byClass.merge(g.defectClass().code(), 1, Integer::sum));
            return new Summary(grade.getCheckpointId(), grade.getGrade().name(), grade.getUpdatedAt(), groups.size(),
                    byClass);
        }
    }

    @Transactional(readOnly = true)
    public List<Summary> summaries(Collection<String> checkpointIds) {
        List<Summary> out = new ArrayList<>();
        for (GradeResult g : grades.findByCheckpointIdIn(Set.copyOf(checkpointIds))) {
            visible(g).ifPresent(v -> out.add(v.summary()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Detail detail(String checkpointId) {
        Visible v = grades.findByCheckpointId(checkpointId).flatMap(this::visible)
                .orElseThrow(() -> DomainException.notFound("공개 검사 결과"));
        Summary s = v.summary();
        OffsetDateTime expiresAt = Times.now().plus(ttl);
        Set<Face> skipped = v.set().getSkippedFaces().keySet();
        List<PhotoView> photos = new ArrayList<>();
        for (Photo p : v.set().getPhotos()) {
            if (!p.isUploaded() || skipped.contains(p.getFace())) {
                continue;
            }
            ViewUrl url = storage.presignCaptureGet(p.getObjectKey(), ttl);
            photos.add(new PhotoView(p.getFace().name(), p.getLight().name(), p.getCamera().name(), url.url()));
            if (url.expiresAt() != null && url.expiresAt().isBefore(expiresAt)) {
                expiresAt = url.expiresAt();
            }
        }
        Map<String, String> groupOf = new HashMap<>();
        for (DefectGroups.Group g : v.groups()) {
            g.defectIds().forEach(id -> groupOf.put(id, g.defectIds().get(0)));
        }
        // 묶음에 들지 못한 흠집(꼭짓점 3개 미만)은 세지 않았으므로 보여 주지도 않는다
        List<PhotoDefectView> list = v.confirmed().stream()
                .filter(d -> groupOf.containsKey(d.getPublicId()))
                .map(d -> new PhotoDefectView(d.getPublicId(), d.getPhoto().getFace().name(),
                        d.getPhoto().getLight().name(), d.getPhoto().getCamera().name(), d.getDefectClass().code(),
                        d.getPhotoPolygon(), groupOf.get(d.getPublicId())))
                .toList();
        return new Detail(s.checkpointId(), s.grade(), s.gradedAt(), s.defectCount(), s.defectsByClass(), expiresAt,
                photos, list);
    }

    private Optional<Visible> visible(GradeResult grade) {
        CaptureSet set = grade.getCaptureSet();
        if (set == null || set.getSource() != CaptureSource.BOOTH) {
            return Optional.empty();
        }
        Set<Face> skipped = set.getSkippedFaces().keySet();
        List<Defect> confirmed = defects.findBySet(set).stream()
                .filter(d -> d.getReviewStatus() == ReviewStatus.CONFIRMED)
                .filter(d -> d.getPhoto().isUploaded() && !skipped.contains(d.getPhoto().getFace()))
                .toList();
        return Optional.of(new Visible(grade, set, confirmed, GradingService.quickGroups(confirmed, judgeable(set))));
    }

    /** 사진이 올라가고 건너뛰지 않은 면. */
    static Set<Face> judgeable(CaptureSet set) {
        Set<Face> faces = EnumSet.noneOf(Face.class);
        set.getPhotos().stream().filter(Photo::isUploaded).forEach(p -> faces.add(p.getFace()));
        faces.removeAll(set.getSkippedFaces().keySet());
        return faces;
    }
}
