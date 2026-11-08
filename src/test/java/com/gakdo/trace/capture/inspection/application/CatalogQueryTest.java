package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService.Detail;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService.Summary;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DismissReason;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;
import com.gakdo.trace.capture.support.FakePhotoStorage;

/** 공개 보유 기기 목록의 흠집(11/8). 저장소는 가짜라 Docker 없이 돈다. */
class CatalogQueryTest {

    private static final String SHA = "c".repeat(64);
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    private static final List<List<Double>> FAR = List.of(List.of(900.0, 600.0), List.of(940.0, 600.0),
            List.of(940.0, 620.0), List.of(900.0, 620.0));

    private DefectRepository defects;
    private GradeResultRepository grades;
    private CatalogQueryService service;

    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        grades = mock(GradeResultRepository.class);
        service = new CatalogQueryService(grades, defects, new FakePhotoStorage(), Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("흠집 수는 확정한 흠집만, 조명만 다른 같은 흠집은 하나로 세고 기각·검수 대기는 뺀다")
    void countsConfirmedGroupsOnly() {
        CaptureSet set = gradedSet("check-00000000000001", CaptureSource.BOOTH);
        Defect l1 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L1), DefectClass.SCRATCH, BOX, 0.9, "v"));
        Defect l2 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L2), DefectClass.DENT, BOX, 0.8, "v"));
        Defect bot = confirmed(Defect.fromBooth(photo(set, Face.BOT, Light.L1), DefectClass.SCRATCH, FAR, 0.7, "v"));
        Defect dismissed = Defect.fromBooth(photo(set, Face.BOT, Light.L2), DefectClass.STAIN, BOX, 0.6, "v");
        dismissed.review(ReviewStatus.DISMISSED, DismissReason.DUST, "admin", OffsetDateTime.now());
        Defect pending = Defect.fromBooth(photo(set, Face.BOT, Light.L3), DefectClass.CRACK, BOX, 0.6, "v");
        when(defects.findBySet(set)).thenReturn(List.of(l1, l2, bot, dismissed, pending));
        GradeResult grade = grade(set, Grade.C);
        when(grades.findByCheckpointIdIn(Set.of(set.getCheckpointId()))).thenReturn(List.of(grade));

        List<Summary> list = service.summaries(List.of(set.getCheckpointId()));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).grade()).isEqualTo("C");
        assertThat(list.get(0).defectCount()).isEqualTo(2);
        // 묶음의 가장 심한 종류로 센다: 긁힘+찍힘 묶음은 찍힘
        assertThat(list.get(0).defectsByClass()).containsEntry("dent", 1).containsEntry("scratch", 1)
                .containsEntry("stain", 0).containsEntry("crack", 0);
    }

    @Test
    @DisplayName("폰으로 찍은 세트는 공개 목록에서 빠지고 상세는 404")
    void phoneSetIsHidden() {
        CaptureSet set = gradedSet("check-00000000000002", CaptureSource.PHONE);
        GradeResult grade = grade(set, Grade.A);
        when(grades.findByCheckpointIdIn(Set.of(set.getCheckpointId()))).thenReturn(List.of(grade));
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(grade));

        assertThat(service.summaries(List.of(set.getCheckpointId()))).isEmpty();
        assertThatThrownBy(() -> service.detail(set.getCheckpointId())).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("상세는 사진 보기 URL과 확정 흠집(원본 px·묶음 ID)을 주고, 같은 묶음은 대표 ID를 공유한다")
    void detailHasPhotosAndGroupedDefects() {
        CaptureSet set = gradedSet("check-00000000000003", CaptureSource.BOOTH);
        Defect l1 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L1), DefectClass.SCRATCH, BOX, 0.9, "v"));
        Defect l2 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L2), DefectClass.SCRATCH, BOX, 0.8, "v"));
        when(defects.findBySet(set)).thenReturn(List.of(l1, l2));
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(grade(set, Grade.B)));

        Detail d = service.detail(set.getCheckpointId());

        assertThat(d.photos()).hasSize(12).allSatisfy(p -> assertThat(p.url()).startsWith("https://"));
        assertThat(d.photoDefects()).hasSize(2);
        assertThat(d.photoDefects().get(0).groupId()).isEqualTo(d.photoDefects().get(1).groupId());
        assertThat(d.defectCount()).isEqualTo(1);
    }

    private static Defect confirmed(Defect d) {
        d.review(ReviewStatus.CONFIRMED, null, "admin", OffsetDateTime.now());
        return d;
    }

    private static GradeResult grade(CaptureSet set, Grade g) {
        GradeResult r = new GradeResult(set.getCheckpointId());
        r.recordGrade(set, g, "grade-rules-v1", List.of(), List.of());
        return r;
    }

    private static CaptureSet gradedSet(String checkpointId, CaptureSource source) {
        if (source == CaptureSource.PHONE) {
            // 공개 여부는 출처만 보므로 폰 세트는 출처·시점만 흉내 낸다
            CaptureSet phone = mock(CaptureSet.class);
            when(phone.getSource()).thenReturn(CaptureSource.PHONE);
            when(phone.getCheckpointId()).thenReturn(checkpointId);
            return phone;
        }
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, source, "booth-1", null,
                CaptureProfile.QUICK, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        return set;
    }

    private static Photo photo(CaptureSet set, Face face, Light light) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1 && p.getLight() == light)
                .findFirst().orElseThrow();
    }

    private static List<PhotoSpec> specs() {
        List<PhotoSpec> specs = new ArrayList<>();
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        return specs;
    }
}
