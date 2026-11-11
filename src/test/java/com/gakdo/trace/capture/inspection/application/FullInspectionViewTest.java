package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.AlignmentMethod;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.FaceAlignment;
import com.gakdo.trace.capture.inspection.domain.Homography;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/** 전체 검사 결과 조회(11/9): 원본 px 흠집과 판정 불가 면을 함께 보여 AI가 무엇을 보고 판정했는지 알 수 있게 한다. */
class FullInspectionViewTest {

    private static final String SHA = "d".repeat(64);
    private static final String CHECKPOINT = "check-00000000000009";
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    private static final List<List<Double>> IDENTITY = List.of(List.of(1.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0),
            List.of(0.0, 0.0, 1.0));
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    @Test
    @DisplayName("전체 검사도 정합에 실패한 옆면의 부스 탐지까지 photoDefects로 보이고, 판정 불가 면과 worker 실패 사유가 함께 온다")
    void fullSetShowsPhotoDefectsAndUnjudgeableFaces() {
        CaptureSet set = CaptureSet.open("rental-000000000000009", CHECKPOINT, CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.FULL, specs());
        long id = 1;
        Set<String> present = new HashSet<>();
        for (Photo p : set.getPhotos()) {
            ReflectionTestUtils.setField(p, "id", id++);
            present.add(p.getPublicId());
        }
        set.complete(present, Map.of(), "key-9", SHA);

        Defect kb = Defect.fromBooth(photo(set, Face.KB), DefectClass.DENT, BOX, 0.4, "v004");
        Defect side = Defect.fromBooth(photo(set, Face.SF), DefectClass.SCRATCH, BOX, 0.7, "v004");
        // 옆면을 뺀 모든 사진은 정합 성공, SF는 worker가 실패 사유를 보냈다
        List<FaceAlignment> aligned = new ArrayList<>();
        for (Photo p : set.getPhotos()) {
            if (p.getFace() == Face.SF) {
                p.markAlignFailed("면이 사진 밖으로 나감(두 변 이상 잘림)", OffsetDateTime.now());
                continue;
            }
            aligned.add(new FaceAlignment(p, AlignmentMethod.CONTOUR, IDENTITY, 1.0, "rectified/" + p.getPublicId()));
        }
        kb.place(Homography.of(IDENTITY));
        set.markInspected();

        DefectRepository defects = mock(DefectRepository.class);
        GradeResultRepository grades = mock(GradeResultRepository.class);
        CaptureSetRepository sets = mock(CaptureSetRepository.class);
        FaceAlignmentRepository alignments = mock(FaceAlignmentRepository.class);
        when(grades.findByCheckpointId(CHECKPOINT)).thenReturn(Optional.empty());
        when(sets.findFirstByCheckpointIdOrderByIdDesc(CHECKPOINT)).thenReturn(Optional.of(set));
        when(defects.findBySet(set)).thenReturn(List.of(kb, side));
        when(alignments.findBySet(set)).thenReturn(aligned);

        var view = new InspectionQueryService(sets, defects, mock(DefectMatchRepository.class), grades, alignments)
                .get(ADMIN, CHECKPOINT);

        assertThat(view.profile()).isEqualTo("FULL");
        assertThat(view.defects()).extracting(InspectionQueryService.DefectView::face).containsExactly("KB");
        assertThat(view.photoDefects()).extracting(InspectionQueryService.PhotoDefectView::face)
                .containsExactly("KB", "SF");
        assertThat(view.photoDefects().get(0).defectId()).isEqualTo(view.defects().get(0).defectId());
        assertThat(view.unjudgeableFaces()).singleElement().satisfies(u -> {
            assertThat(u.face()).isEqualTo("SF");
            assertThat(u.reason()).contains("CAM1").contains("두 변 이상 잘림");
        });
    }

    @Test
    @DisplayName("정합 결과가 오기 전의 전체 검사는 정합 실패를 따지지 않고 건너뛴 면만 판정 불가로 보인다")
    void beforeInspectionOnlySkippedFaces() {
        CaptureSet set = CaptureSet.open("rental-000000000000009", CHECKPOINT, CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.FULL, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(Face.SCR1, "스킵"), "key-9", SHA);
        FaceAlignmentRepository alignments = mock(FaceAlignmentRepository.class);

        var service = new InspectionQueryService(mock(CaptureSetRepository.class), mock(DefectRepository.class),
                mock(DefectMatchRepository.class), mock(GradeResultRepository.class), alignments);

        assertThat(service.unjudgeableFaces(set)).containsExactly(
                new InspectionQueryService.UnjudgeableFace("SCR1", "건너뜀: 스킵"));
    }

    private static Photo photo(CaptureSet set, Face face) {
        return set.getPhotos().stream().filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1
                && p.getLight() == Light.L1).findFirst().orElseThrow();
    }

    private static List<PhotoSpec> specs() {
        List<PhotoSpec> specs = new ArrayList<>();
        OffsetDateTime at = OffsetDateTime.parse("2026-11-09T14:00:00+09:00");
        for (Face face : List.of(Face.BOT, Face.TOP, Face.KB)) {
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM1, "jpg", SHA, at));
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM2, "jpg", SHA, at));
        }
        for (Face face : List.of(Face.SF, Face.SB, Face.SL, Face.SR, Face.SCR0)) {
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM1, "jpg", SHA, at));
        }
        return specs;
    }
}
