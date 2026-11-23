// 이 파일이 확인하는 것: 빠른 검사 결과 조회에서 사진별 흠집(photoDefects)을 어디까지 보여 주는지.
// 사진 목록(/media)과 같은 범위, 즉 완료 때 실제로 올라간 사진·건너뛰지 않은 면의 흠집만 싣는다.

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
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/** 빠른 검사 결과 조회: photoDefects는 /media와 같은 범위(완료 때 올라간 사진, 건너뛰지 않은 면)만 싣는다. */
class QuickInspectionViewTest {

    private static final String SHA = "c".repeat(64);
    private static final String CHECKPOINT = "check-00000000000001";
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    // [상황] 부스 빠른 검사에서 바닥(BOT) 사진은 올라갔고 윗면(TOP)은 "업로드 실패"로 건너뛴 채 완료됐다.
    //        그런데 탐지 결과는 TOP·BOT 둘 다에 흠집이 하나씩 먼저 와 있었다.
    // [기대] 결과 화면의 사진별 흠집에는 BOT만 남고 건너뛴 TOP 흠집은 빠진다(사진도 없는 면에 흠집을 보여 주지 않음).
    @Test
    @DisplayName("건너뛴 면에 먼저 온 탐지는 빠른 검사 결과의 photoDefects에서 빠진다")
    void skippedFaceDefectsAreHidden() {
        CaptureSet set = CaptureSet.open("rental-000000000000001", CHECKPOINT, CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.QUICK, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().stream().filter(p -> p.getFace() == Face.BOT).forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(Face.TOP, "업로드 실패"), "key-1", SHA);

        DefectRepository defects = mock(DefectRepository.class);
        GradeResultRepository grades = mock(GradeResultRepository.class);
        CaptureSetRepository sets = mock(CaptureSetRepository.class);
        when(grades.findByCheckpointId(CHECKPOINT)).thenReturn(Optional.empty());
        when(sets.findFirstByCheckpointIdOrderByIdDesc(CHECKPOINT)).thenReturn(Optional.of(set));
        when(defects.findBySet(set)).thenReturn(List.of(
                Defect.fromBooth(photo(set, Face.TOP), DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT), DefectClass.SCRATCH, BOX, 0.9, "v")));

        var view = new InspectionQueryService(sets, defects, mock(DefectMatchRepository.class), grades,
                mock(FaceAlignmentRepository.class))
                .get(ADMIN, CHECKPOINT);

        assertThat(view.defects()).isEmpty();
        assertThat(view.photoDefects()).extracting(InspectionQueryService.PhotoDefectView::face)
                .containsExactly("BOT");
    }

    // 테스트 도구: 세트에서 그 면의 CAM1·L1 사진 하나를 찾는다.
    private static Photo photo(CaptureSet set, Face face) {
        return set.getPhotos().stream().filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1
                && p.getLight() == Light.L1).findFirst().orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
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
