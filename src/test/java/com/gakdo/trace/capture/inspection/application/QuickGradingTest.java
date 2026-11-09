package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.ArgumentCaptor;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/** 빠른 검사 세트를 정합 없이 등급 매기기(contracts specs/005-booth-quick-set). 저장소는 가짜라 Docker 없이 돈다. */
class QuickGradingTest {

    private static final String SHA = "b".repeat(64);
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));

    private DefectRepository defects;
    private DefectMatchRepository matches;
    private GradeResultRepository grades;
    private GradeEventPublisher events;
    private GradingService grading;

    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        matches = mock(DefectMatchRepository.class);
        grades = mock(GradeResultRepository.class);
        events = mock(GradeEventPublisher.class);
        when(grades.findByCheckpointId(anyString())).thenReturn(Optional.empty());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        grading = new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, events);
    }

    @Test
    @DisplayName("빠른 검사 세트는 완료와 부스 탐지가 있으면 정합 없이 등급이 나오고, 흠집 0개면 A(문제 없음)")
    void noDefectsIsGradeA() {
        CaptureSet set = completedQuickSet();
        set.markDetectionsReceived();
        when(defects.findBySet(set)).thenReturn(List.of());

        assertThat(grading.gradeIfReady(set)).isTrue();

        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        assertThat(sent.getValue().grade()).isEqualTo(Grade.A);
        assertThat(sent.getValue().pins()).isEmpty();
        assertThat(sent.getValue().photos()).hasSize(12);
        assertThat(sent.getValue().compared()).isFalse(); // 앞 시점과 비교하지 않음: 빈 newDefectIds가 '신규 없음'이 아니다
    }

    @Test
    @DisplayName("빠른 검사 세트의 긁힘 2개는 B, 찍힘이 있으면 C(문제 있음)")
    void defectsGiveGrade() {
        CaptureSet set = completedQuickSet();
        set.markDetectionsReceived();
        Photo top = photo(set, Face.TOP, Camera.CAM1);
        Photo bot = photo(set, Face.BOT, Camera.CAM2);
        when(defects.findBySet(set)).thenReturn(List.of(
                Defect.fromBooth(top, DefectClass.SCRATCH, BOX, 0.9, "yolo11-seg-v001"),
                Defect.fromBooth(bot, DefectClass.SCRATCH, BOX, 0.8, "yolo11-seg-v001")));
        grading.gradeIfReady(set);
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        assertThat(sent.getValue().grade()).isEqualTo(Grade.B);

        CaptureSet dented = completedQuickSet();
        dented.markDetectionsReceived();
        when(defects.findBySet(dented)).thenReturn(List.of(
                Defect.fromBooth(photo(dented, Face.TOP, Camera.CAM1), DefectClass.DENT, BOX, 0.9, "v")));
        GradeEventPublisher second = mock(GradeEventPublisher.class);
        new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, second).gradeIfReady(dented);
        ArgumentCaptor<CheckpointGradedEvent> again = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(second).publishGraded(again.capture());
        assertThat(again.getValue().grade()).isEqualTo(Grade.C);
    }

    @Test
    @DisplayName("같은 카메라의 L1·L2·L3 사진에 함께 잡힌 같은 흠집은 한 번만 세고, 다른 카메라라도 면 위 자리가 먼 흠집은 따로 센다")
    void sameSpotAcrossLightsCountsOnce() {
        CaptureSet set = completedQuickSet();
        Photo topL1 = photo(set, Face.TOP, Camera.CAM1, Light.L1);
        Photo topL2 = photo(set, Face.TOP, Camera.CAM1, Light.L2);
        Photo topL3 = photo(set, Face.TOP, Camera.CAM1, Light.L3);
        Photo topCam2 = photo(set, Face.TOP, Camera.CAM2, Light.L2);
        List<Defect> found = List.of(
                Defect.fromBooth(topL1, DefectClass.SCRATCH, BOX, 0.7, "v"),
                Defect.fromBooth(topL2, DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(topL3, DefectClass.SCRATCH, BOX, 0.8, "v"),
                // CAM2 사진의 같은 px 자리는 면 위로 옮기면 CAM1 자리와 4cm 넘게 떨어진 다른 곳이다
                Defect.fromBooth(topCam2, DefectClass.SCRATCH, BOX, 0.9, "v"));
        assertThat(GradingService.quickGroups(found, Set.of(Face.TOP, Face.BOT))).hasSize(2);
    }

    @Test
    @DisplayName("CAM1·CAM2 사진에 함께 찍힌 같은 흠집(11/9 X391 바닥)은 L1·L2 네 장에 잡혀도 하나로 세어 긁힘 1개 → B")
    void sameSpotAcrossCamerasCountsOnce() {
        CaptureSet set = completedQuickSet();
        set.markDetectionsReceived();
        // 11/9 X391 출고 빠른 검사 바닥: CAM1 (0.661, 0.70), CAM2 (0.746, 0.689) 사진 비율 자리의 같은 긁힘
        List<List<Double>> cam1 = box(0.661 * 1920, 0.70 * 1080);
        List<List<Double>> cam2 = box(0.746 * 1920, 0.689 * 1080);
        when(defects.findBySet(set)).thenReturn(List.of(
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1, Light.L1), DefectClass.SCRATCH, cam1, 0.48, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2, Light.L1), DefectClass.SCRATCH, cam2, 0.30, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1, Light.L2), DefectClass.SCRATCH, cam1, 0.57, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2, Light.L2), DefectClass.SCRATCH, cam2, 0.37, "v")));

        grading.gradeIfReady(set);

        ArgumentCaptor<GradeResult> saved = ArgumentCaptor.forClass(GradeResult.class);
        verify(grades).save(saved.capture());
        assertThat(saved.getValue().getGrade()).isEqualTo(Grade.B);
        assertThat(saved.getValue().getReasons().get(0)).startsWith("긁힘 1개");
        assertThat(saved.getValue().getReasons()).contains("CAM1·CAM2 두 카메라에 함께 찍힌 같은 흠집 1개는 하나로 셈");
    }

    @Test
    @DisplayName("한 카메라에 따로 잡힌 가까운 두 흠집은 다른 카메라의 흠집 하나와 함께 묶이지 않는다(가까운 쪽 하나만 합침)")
    void oneToOneAcrossCameras() {
        CaptureSet set = completedQuickSet();
        // CAM1에 1.5cm 떨어진 두 긁힘(같은 카메라라 px로는 묶이지 않음), CAM2에는 그중 하나와 같은 자리 하나
        List<Defect> found = List.of(
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1), DefectClass.SCRATCH, box(1269, 756), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1), DefectClass.SCRATCH, box(1269, 820), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2), DefectClass.SCRATCH, box(1432, 744), 0.9, "v"));
        assertThat(GradingService.quickGroups(found, Set.of(Face.BOT))).hasSize(2);
    }

    @Test
    @DisplayName("다른 면의 흠집이나 오염과 긁힘은 카메라 사이에서도 합치지 않는다")
    void crossCameraKeepsFaceAndStain() {
        CaptureSet set = completedQuickSet();
        List<Defect> found = List.of(
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1), DefectClass.SCRATCH, box(1269, 756), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2), DefectClass.STAIN, box(1432, 744), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.TOP, Camera.CAM2), DefectClass.SCRATCH, box(1432, 744), 0.9, "v"));
        assertThat(GradingService.quickGroups(found, Set.of(Face.TOP, Face.BOT))).hasSize(3);
    }

    /** 가운데가 (x, y) px인 작은 사각형(6×4 px) */
    private static List<List<Double>> box(double x, double y) {
        return List.of(List.of(x - 3, y - 2), List.of(x + 3, y - 2), List.of(x + 3, y + 2), List.of(x - 3, y + 2));
    }

    @Test
    @DisplayName("빠른 검사 세트도 부스 탐지가 오기 전에는 등급을 매기지 않는다")
    void waitsForDetections() {
        CaptureSet set = completedQuickSet();
        assertThat(grading.gradeIfReady(set)).isFalse();
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        verify(events, never()).publishGraded(any());
    }

    @Test
    @DisplayName("완료 전(UPLOADING)인 빠른 검사 세트는 탐지가 와도 등급을 매기지 않는다")
    void waitsForCompletion() {
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        set.markDetectionsReceived();
        assertThat(grading.gradeIfReady(set)).isFalse();
        verify(events, never()).publishGraded(any());
    }

    @Test
    @DisplayName("부스 빠른 검사로 찍어도 앞서 등급 난 시점이 1개면 수령(RCV), 2개면 반납(RET)으로 보낸다")
    void boothQuickCanBeReceiveOrReturn() {
        assertThat(kindAfter(1)).isEqualTo(CheckpointKind.RCV);
        assertThat(kindAfter(2)).isEqualTo(CheckpointKind.RET);
        assertThat(kindAfter(0)).isEqualTo(CheckpointKind.OUT);
    }

    @Test
    @DisplayName("앞 시점도 빠른 검사면 흠집 수를 '앞 시점 비교(개수만)' 근거로 나란히 보이고, 등급은 이번 흠집으로만 정한다")
    void quickComparesCountWithPrevious() {
        CaptureSet out = completedQuickSet("check-00000000000001");
        when(defects.findBySet(out)).thenReturn(List.of());
        GradeResult previous = new GradeResult(out.getCheckpointId());
        previous.recordGrade(out, Grade.A, "grade-rules-v1", List.of(), List.of());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.of(previous));
        when(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(anyString(), anyString(), any()))
                .thenReturn(1L);

        CaptureSet ret = completedQuickSet("check-00000000000002");
        ret.markDetectionsReceived();
        when(defects.findBySet(ret)).thenReturn(List.of(
                Defect.fromBooth(photo(ret, Face.TOP, Camera.CAM1), DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(photo(ret, Face.BOT, Camera.CAM2), DefectClass.STAIN, BOX, 0.9, "v")));
        ArgumentCaptor<GradeResult> saved = ArgumentCaptor.forClass(GradeResult.class);

        grading.gradeIfReady(ret);

        verify(grades).save(saved.capture());
        assertThat(saved.getValue().getReasons()).contains("앞 시점 비교(개수만): 흠집 0개 → 1개(1개 늘어남, 오염 제외)");
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        assertThat(sent.getValue().checkpointType()).isEqualTo(CheckpointKind.RCV);
        assertThat(sent.getValue().grade()).isEqualTo(Grade.B);
    }

    @Test
    @DisplayName("앞 시점을 다시 찍어 재등급해도 그 시점의 첫 등급 시각보다 먼저 등급 난 시점만 세어 종류가 바뀌지 않는다")
    void regradeKeepsKindByFirstGradeTime() {
        CaptureSet set = completedQuickSet();
        set.markDetectionsReceived();
        when(defects.findBySet(set)).thenReturn(List.of());
        GradeResult existing = new GradeResult(set.getCheckpointId());
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(existing));

        grading.gradeIfReady(set);

        verify(grades).countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(set.getRentalId(),
                set.getCheckpointId(), existing.getCreatedAt());
    }

    private CheckpointKind kindAfter(long earlier) {
        when(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(anyString(), anyString(), any()))
                .thenReturn(earlier);
        CaptureSet set = completedQuickSet();
        set.markDetectionsReceived();
        when(defects.findBySet(set)).thenReturn(List.of());
        GradeEventPublisher publisher = mock(GradeEventPublisher.class);
        new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, publisher).gradeIfReady(set);
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(publisher).publishGraded(sent.capture());
        return sent.getValue().checkpointType();
    }

    private static CaptureSet completedQuickSet() {
        return completedQuickSet("check-00000000000001");
    }

    private static CaptureSet completedQuickSet(String checkpointId) {
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        return set;
    }

    private static Photo photo(CaptureSet set, Face face, Camera camera) {
        return photo(set, face, camera, Light.L1);
    }

    private static Photo photo(CaptureSet set, Face face, Camera camera, Light light) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == camera && p.getLight() == light).findFirst()
                .orElseThrow();
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
