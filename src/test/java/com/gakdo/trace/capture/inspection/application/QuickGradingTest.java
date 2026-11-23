// 이 파일이 확인하는 것: 부스 빠른 검사 세트의 등급 매기기(contracts specs/005-booth-quick-set).
// 빠른 검사는 면 펼치기·정합 없이, 부스(Jetson)가 보낸 탐지 결과만으로 바로 등급을 낸다.
// 같은 흠집이 조명(L1~L3)이나 카메라(CAM1·CAM2)만 달리 여러 번 잡혀도 한 번만 세는지, 시점 종류(출고·수령·반납)를 맞게 정하는지 본다.
// (이 파일은 사람 검수를 끈 설정으로 시험하므로 탐지가 오면 바로 등급이 난다. 사람 검수는 ReviewGradingTest에서 본다.)

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

    // 테스트 도구: 가짜 저장소·가짜 이벤트 발행기로 등급 서비스를 만든다(앞 시점 등급 없음).
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

    // [상황] 다 올린 빠른 검사 세트에 부스 탐지 결과가 왔고 흠집은 0개다.
    // [기대] 정합 없이 바로 등급 완료(GRADED)가 되고 등급 A(문제 없음) 이벤트가 나간다(핀 없음, 사진 12장).
    //        앞 시점과 비교하지 않았다는 표시(compared=false)도 실린다(새 흠집 목록이 비어 있어도 "새 흠집 없음"이라는 뜻이 아님).
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

    // [상황] 한 세트는 윗면·바닥에 긁힘이 하나씩, 다른 세트는 윗면에 찍힘이 하나 있다.
    // [기대] 긁힘 2개는 B, 찍힘이 있으면 C(문제 있음)다.
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

    // [상황] 윗면 CAM1의 L1·L2·L3 사진 같은 자리에 긁힘이 세 번 잡혔고, CAM2 사진의 같은 픽셀 자리에도 하나 잡혔다.
    // [기대] CAM1 세 개는 같은 흠집이라 하나로 센다. CAM2 것은 면 위로 옮기면 4cm 넘게 떨어진 다른 곳이라 따로 센다. 모두 2개.
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

    // [상황] 실제 11/9 X391 기기 출고 빠른 검사 바닥에서, 같은 긁힘이 CAM1·CAM2 × L1·L2 사진 네 장에 잡혔다.
    // [기대] 하나로 세어 긁힘 1개 → B이고, 근거에 "두 카메라에 함께 찍힌 같은 흠집 1개는 하나로 셈"이 남는다.
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

    // [상황] CAM1에 1.5cm 떨어진 긁힘 두 개, CAM2에는 그중 하나와 같은 자리에 긁힘 하나가 있다.
    // [기대] CAM2 것은 가까운 쪽 하나와만 합쳐져 모두 2개다(셋이 한 덩어리로 묶이지 않음).
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

    // [상황] 바닥 CAM1 긁힘, 바닥 CAM2 같은 자리 오염, 윗면 CAM2 긁힘이 있다.
    // [기대] 오염과 긁힘은 합치지 않고, 다른 면끼리도 합치지 않아 3개로 센다.
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
    // 테스트 도구: 가운데가 (x, y) px인 작은 사각형(6×4 px) 흠집 모양.
    private static List<List<Double>> box(double x, double y) {
        return List.of(List.of(x - 3, y - 2), List.of(x + 3, y - 2), List.of(x + 3, y + 2), List.of(x - 3, y + 2));
    }

    // [상황] 빠른 검사 세트는 다 올렸지만 부스 탐지 결과가 아직 안 왔다.
    // [기대] 등급을 매기지 않고 "올림 완료"(UPLOADED) 그대로이며 이벤트도 없다.
    @Test
    @DisplayName("빠른 검사 세트도 부스 탐지가 오기 전에는 등급을 매기지 않는다")
    void waitsForDetections() {
        CaptureSet set = completedQuickSet();
        assertThat(grading.gradeIfReady(set)).isFalse();
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        verify(events, never()).publishGraded(any());
    }

    // [상황] 탐지 결과는 왔지만 세트가 아직 완료되지 않았다(UPLOADING).
    // [기대] 등급을 매기지 않는다.
    @Test
    @DisplayName("완료 전(UPLOADING)인 빠른 검사 세트는 탐지가 와도 등급을 매기지 않는다")
    void waitsForCompletion() {
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        set.markDetectionsReceived();
        assertThat(grading.gradeIfReady(set)).isFalse();
        verify(events, never()).publishGraded(any());
    }

    // [상황] 같은 렌탈에서 앞서 등급 난 시점 수가 1, 2, 0개인 상태로 부스 빠른 검사 등급을 낸다.
    // [기대] 각각 수령(RCV), 반납(RET), 출고(OUT)로 보낸다(부스에서 찍었다고 출고로 고정하지 않음).
    @Test
    @DisplayName("부스 빠른 검사로 찍어도 앞서 등급 난 시점이 1개면 수령(RCV), 2개면 반납(RET)으로 보낸다")
    void boothQuickCanBeReceiveOrReturn() {
        assertThat(kindAfter(1)).isEqualTo(CheckpointKind.RCV);
        assertThat(kindAfter(2)).isEqualTo(CheckpointKind.RET);
        assertThat(kindAfter(0)).isEqualTo(CheckpointKind.OUT);
    }

    // [상황] 앞 시점(출고) 빠른 검사는 흠집 0개로 A였다. 이번 시점에는 윗면 긁힘 1개와 바닥 오염 1개가 있다.
    // [기대] 근거에 "앞 시점 비교(개수만): 흠집 0개 → 1개(1개 늘어남, 오염 제외)"가 나란히 보인다.
    //        시점 종류는 수령(RCV), 등급은 이번 흠집만으로 정해 B다.
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

    // [상황] 이미 등급이 있는 시점을 다시 찍어 재등급한다.
    // [기대] 시점 종류를 셀 때 "그 시점의 첫 등급 시각보다 먼저 등급 난 시점"만 센다(나중 시점이 생겨도 출고가 반납으로 바뀌지 않음).
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

    // 테스트 도구: 앞서 등급 난 시점 수를 정해 두고 빠른 검사 등급을 내서, 이벤트에 실린 시점 종류를 돌려준다.
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

    // 테스트 도구: 다 올린 부스 빠른 검사 세트(기본 시점 ID)를 만든다.
    private static CaptureSet completedQuickSet() {
        return completedQuickSet("check-00000000000001");
    }

    // 테스트 도구: 다 올린 부스 빠른 검사 세트를 시점 ID를 정해 만든다.
    private static CaptureSet completedQuickSet(String checkpointId) {
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        return set;
    }

    // 테스트 도구: 세트에서 그 면·카메라의 L1 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera) {
        return photo(set, face, camera, Light.L1);
    }

    // 테스트 도구: 세트에서 그 면·카메라·조명의 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera, Light light) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == camera && p.getLight() == light).findFirst()
                .orElseThrow();
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
