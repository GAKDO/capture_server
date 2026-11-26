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

    // 가짜 사진 해시값(64자리 "b")
    private static final String SHA = "b".repeat(64);
    // 사진 위 흠집 모양: (10,10)~(50,30) 네모
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));

    // 가짜 흠집 저장소
    private DefectRepository defects;
    // 가짜 흠집 짝 저장소
    private DefectMatchRepository matches;
    // 가짜 등급 결과 저장소
    private GradeResultRepository grades;
    // 가짜 등급 이벤트 발행기
    private GradeEventPublisher events;
    // 시험할 대상: 등급 서비스
    private GradingService grading;

    // 테스트 도구: 가짜 저장소·가짜 이벤트 발행기로 등급 서비스를 만든다(앞 시점 등급 없음).
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 흠집 저장소를 가짜로 만든다
        defects = mock(DefectRepository.class);
        // 준비: 흠집 짝 저장소를 가짜로 만든다
        matches = mock(DefectMatchRepository.class);
        // 준비: 등급 결과 저장소를 가짜로 만든다
        grades = mock(GradeResultRepository.class);
        // 준비: 이벤트 발행기를 가짜로 만든다
        events = mock(GradeEventPublisher.class);
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. anyString() = 아무 글자
        // 준비: 어떤 시점의 기존 등급을 물어도 "없음"으로 답하게 한다
        when(grades.findByCheckpointId(anyString())).thenReturn(Optional.empty());
        // 준비: 같은 렌탈의 앞 시점 등급도 "없음"으로 답하게 한다
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        // 준비: 사람 검수를 끈(기본) 등급 서비스를 만든다
        grading = new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, events);
    }

    // [상황] 다 올린 빠른 검사 세트에 부스 탐지 결과가 왔고 흠집은 0개다.
    // [기대] 정합 없이 바로 등급 완료(GRADED)가 되고 등급 A(문제 없음) 이벤트가 나간다(핀 없음, 사진 12장).
    //        앞 시점과 비교하지 않았다는 표시(compared=false)도 실린다(새 흠집 목록이 비어 있어도 "새 흠집 없음"이라는 뜻이 아님).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("빠른 검사 세트는 완료와 부스 탐지가 있으면 정합 없이 등급이 나오고, 흠집 0개면 A(문제 없음)")
    void noDefectsIsGradeA() {
        // 준비: 다 올린 빠른 검사 세트를 만든다
        CaptureSet set = completedQuickSet();
        // 준비: 부스 탐지 결과를 받았다고 표시한다
        set.markDetectionsReceived();
        // 준비: 이 세트의 흠집은 0개라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of());

        // (도구) assertThat(값).isTrue() / isFalse() = 값이 참 / 거짓이어야 한다는 검사
        // 실행+확인: 등급을 매기면 "매겼다(true)"여야 한다
        assertThat(grading.gradeIfReady(set)).isTrue();

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 세트는 등급 완료(GRADED)여야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
        // (도구) ArgumentCaptor = 가짜에게 넘겨진 인자를 붙잡아 두는 도구 (무엇을 보냈는지 꺼내 보려고)
        // 확인 준비: 보낸 등급 이벤트를 붙잡을 도구를 만든다
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        // (도구) verify(가짜).메서드(...) = 그 메서드가 불렸어야 한다는 검사
        // 확인: 등급 이벤트를 보냈어야 하고, 그 이벤트를 붙잡는다
        verify(events).publishGraded(sent.capture());
        // 확인: 등급은 A여야 한다
        assertThat(sent.getValue().grade()).isEqualTo(Grade.A);
        // (도구) isEmpty() = 목록이 비어 있어야 한다는 검사
        // 확인: 흠집 핀이 없어야 한다
        assertThat(sent.getValue().pins()).isEmpty();
        // (도구) assertThat(목록).hasSize(n) = 목록 개수가 n이어야 한다는 검사
        // 확인: 사진 12장이 실려야 한다
        assertThat(sent.getValue().photos()).hasSize(12);
        // 확인: 앞 시점과 비교하지 않았다는 표시(false)가 실려야 한다
        assertThat(sent.getValue().compared()).isFalse(); // 앞 시점과 비교하지 않음: 빈 newDefectIds가 '신규 없음'이 아니다
    }

    // [상황] 한 세트는 윗면·바닥에 긁힘이 하나씩, 다른 세트는 윗면에 찍힘이 하나 있다.
    // [기대] 긁힘 2개는 B, 찍힘이 있으면 C(문제 있음)다.
    @Test
    @DisplayName("빠른 검사 세트의 긁힘 2개는 B, 찍힘이 있으면 C(문제 있음)")
    void defectsGiveGrade() {
        // 준비: 첫 번째 세트를 만들고
        CaptureSet set = completedQuickSet();
        // 탐지 결과를 받았다고 표시한다
        set.markDetectionsReceived();
        // 준비: 윗면 CAM1 사진
        Photo top = photo(set, Face.TOP, Camera.CAM1);
        // 준비: 바닥 CAM2 사진
        Photo bot = photo(set, Face.BOT, Camera.CAM2);
        // 준비: 이 세트의 흠집은 윗면 긁힘·바닥 긁힘 2개라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(
                Defect.fromBooth(top, DefectClass.SCRATCH, BOX, 0.9, "yolo11-seg-v001"),
                Defect.fromBooth(bot, DefectClass.SCRATCH, BOX, 0.8, "yolo11-seg-v001")));
        // 실행: 등급을 매긴다
        grading.gradeIfReady(set);
        // 보낸 이벤트를 붙잡을 도구
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        // 확인: 이벤트를 보냈어야 하고 붙잡는다
        verify(events).publishGraded(sent.capture());
        // 확인: 긁힘 2개는 B여야 한다
        assertThat(sent.getValue().grade()).isEqualTo(Grade.B);

        // 준비: 두 번째 세트를 만들고
        CaptureSet dented = completedQuickSet();
        // 탐지 결과를 받았다고 표시한다
        dented.markDetectionsReceived();
        // 준비: 이 세트의 흠집은 윗면 찍힘 1개라고 답하게 한다
        when(defects.findBySet(dented)).thenReturn(List.of(
                Defect.fromBooth(photo(dented, Face.TOP, Camera.CAM1), DefectClass.DENT, BOX, 0.9, "v")));
        // 준비: 새 가짜 발행기 (앞 세트 이벤트와 섞이지 않게)
        GradeEventPublisher second = mock(GradeEventPublisher.class);
        // 실행: 새 발행기로 등급 서비스를 만들어 이 세트에 등급을 매긴다
        new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, second).gradeIfReady(dented);
        // 보낸 이벤트를 붙잡을 도구
        ArgumentCaptor<CheckpointGradedEvent> again = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        // 확인: 이벤트를 보냈어야 하고 붙잡는다
        verify(second).publishGraded(again.capture());
        // 확인: 찍힘이 있으면 C여야 한다
        assertThat(again.getValue().grade()).isEqualTo(Grade.C);
    }

    // [상황] 윗면 CAM1의 L1·L2·L3 사진 같은 자리에 긁힘이 세 번 잡혔고, CAM2 사진의 같은 픽셀 자리에도 하나 잡혔다.
    // [기대] CAM1 세 개는 같은 흠집이라 하나로 센다. CAM2 것은 면 위로 옮기면 4cm 넘게 떨어진 다른 곳이라 따로 센다. 모두 2개.
    @Test
    @DisplayName("같은 카메라의 L1·L2·L3 사진에 함께 잡힌 같은 흠집은 한 번만 세고, 다른 카메라라도 면 위 자리가 먼 흠집은 따로 센다")
    void sameSpotAcrossLightsCountsOnce() {
        // 준비: 다 올린 빠른 검사 세트를 만든다
        CaptureSet set = completedQuickSet();
        // 준비: 윗면 CAM1 L1 사진
        Photo topL1 = photo(set, Face.TOP, Camera.CAM1, Light.L1);
        // 준비: 윗면 CAM1 L2 사진
        Photo topL2 = photo(set, Face.TOP, Camera.CAM1, Light.L2);
        // 준비: 윗면 CAM1 L3 사진
        Photo topL3 = photo(set, Face.TOP, Camera.CAM1, Light.L3);
        // 준비: 윗면 CAM2 L2 사진
        Photo topCam2 = photo(set, Face.TOP, Camera.CAM2, Light.L2);
        // 준비: 네 사진의 같은 픽셀 자리(BOX)에 잡힌 긁힘 4개
        List<Defect> found = List.of(
                Defect.fromBooth(topL1, DefectClass.SCRATCH, BOX, 0.7, "v"),
                Defect.fromBooth(topL2, DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(topL3, DefectClass.SCRATCH, BOX, 0.8, "v"),
                // CAM2 사진의 같은 px 자리는 면 위로 옮기면 CAM1 자리와 4cm 넘게 떨어진 다른 곳이다
                Defect.fromBooth(topCam2, DefectClass.SCRATCH, BOX, 0.9, "v"));
        // 실행+확인: 윗면·바닥 기준으로 묶으면 2개여야 한다 (CAM1 세 개 → 1, CAM2 → 1)
        assertThat(GradingService.quickGroups(found, Set.of(Face.TOP, Face.BOT))).hasSize(2);
    }

    // [상황] 실제 11/9 X391 기기 출고 빠른 검사 바닥에서, 같은 긁힘이 CAM1·CAM2 × L1·L2 사진 네 장에 잡혔다.
    // [기대] 하나로 세어 긁힘 1개 → B이고, 근거에 "두 카메라에 함께 찍힌 같은 흠집 1개는 하나로 셈"이 남는다.
    @Test
    @DisplayName("CAM1·CAM2 사진에 함께 찍힌 같은 흠집(11/9 X391 바닥)은 L1·L2 네 장에 잡혀도 하나로 세어 긁힘 1개 → B")
    void sameSpotAcrossCamerasCountsOnce() {
        // 준비: 다 올린 빠른 검사 세트를 만들고
        CaptureSet set = completedQuickSet();
        // 탐지 결과를 받았다고 표시한다
        set.markDetectionsReceived();
        // 11/9 X391 출고 빠른 검사 바닥: CAM1 (0.661, 0.70), CAM2 (0.746, 0.689) 사진 비율 자리의 같은 긁힘
        // 준비: CAM1 사진(1920×1080)에서 흠집 자리 상자
        List<List<Double>> cam1 = box(0.661 * 1920, 0.70 * 1080);
        // 준비: CAM2 사진에서 같은 흠집 자리 상자
        List<List<Double>> cam2 = box(0.746 * 1920, 0.689 * 1080);
        // 준비: 바닥 CAM1·CAM2 × L1·L2 사진 네 장에 잡힌 긁힘 4개를 돌려주게 한다
        when(defects.findBySet(set)).thenReturn(List.of(
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1, Light.L1), DefectClass.SCRATCH, cam1, 0.48, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2, Light.L1), DefectClass.SCRATCH, cam2, 0.30, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1, Light.L2), DefectClass.SCRATCH, cam1, 0.57, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2, Light.L2), DefectClass.SCRATCH, cam2, 0.37, "v")));

        // 실행: 등급을 매긴다
        grading.gradeIfReady(set);

        // 저장된 등급 결과를 붙잡을 도구
        ArgumentCaptor<GradeResult> saved = ArgumentCaptor.forClass(GradeResult.class);
        // 확인: 등급 결과를 저장했어야 하고 붙잡는다
        verify(grades).save(saved.capture());
        // 확인: 긁힘 1개로 세어 B여야 한다
        assertThat(saved.getValue().getGrade()).isEqualTo(Grade.B);
        // (도구) startsWith(글자) = 그 글자로 시작해야 한다는 검사
        // 확인: 첫 근거가 "긁힘 1개"로 시작해야 한다
        assertThat(saved.getValue().getReasons().get(0)).startsWith("긁힘 1개");
        // (도구) contains(...) = 목록에 이 값이 들어 있어야 한다는 검사
        // 확인: 두 카메라 흠집을 하나로 셌다는 근거가 있어야 한다
        assertThat(saved.getValue().getReasons()).contains("CAM1·CAM2 두 카메라에 함께 찍힌 같은 흠집 1개는 하나로 셈");
    }

    // [상황] CAM1에 1.5cm 떨어진 긁힘 두 개, CAM2에는 그중 하나와 같은 자리에 긁힘 하나가 있다.
    // [기대] CAM2 것은 가까운 쪽 하나와만 합쳐져 모두 2개다(셋이 한 덩어리로 묶이지 않음).
    @Test
    @DisplayName("한 카메라에 따로 잡힌 가까운 두 흠집은 다른 카메라의 흠집 하나와 함께 묶이지 않는다(가까운 쪽 하나만 합침)")
    void oneToOneAcrossCameras() {
        // 준비: 다 올린 빠른 검사 세트를 만든다
        CaptureSet set = completedQuickSet();
        // CAM1에 1.5cm 떨어진 두 긁힘(같은 카메라라 px로는 묶이지 않음), CAM2에는 그중 하나와 같은 자리 하나
        // 준비: 바닥 CAM1 긁힘 2개(1.5cm 떨어짐)와 CAM2 긁힘 1개
        List<Defect> found = List.of(
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1), DefectClass.SCRATCH, box(1269, 756), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1), DefectClass.SCRATCH, box(1269, 820), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2), DefectClass.SCRATCH, box(1432, 744), 0.9, "v"));
        // 실행+확인: 묶으면 2개여야 한다 (CAM2는 가까운 하나와만 합침)
        assertThat(GradingService.quickGroups(found, Set.of(Face.BOT))).hasSize(2);
    }

    // [상황] 바닥 CAM1 긁힘, 바닥 CAM2 같은 자리 오염, 윗면 CAM2 긁힘이 있다.
    // [기대] 오염과 긁힘은 합치지 않고, 다른 면끼리도 합치지 않아 3개로 센다.
    @Test
    @DisplayName("다른 면의 흠집이나 오염과 긁힘은 카메라 사이에서도 합치지 않는다")
    void crossCameraKeepsFaceAndStain() {
        // 준비: 다 올린 빠른 검사 세트를 만든다
        CaptureSet set = completedQuickSet();
        // 준비: 바닥 CAM1 긁힘, 바닥 CAM2 오염(같은 자리), 윗면 CAM2 긁힘
        List<Defect> found = List.of(
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM1), DefectClass.SCRATCH, box(1269, 756), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT, Camera.CAM2), DefectClass.STAIN, box(1432, 744), 0.9, "v"),
                Defect.fromBooth(photo(set, Face.TOP, Camera.CAM2), DefectClass.SCRATCH, box(1432, 744), 0.9, "v"));
        // 실행+확인: 묶으면 3개여야 한다 (오염과 긁힘, 다른 면끼리는 안 합침)
        assertThat(GradingService.quickGroups(found, Set.of(Face.TOP, Face.BOT))).hasSize(3);
    }

    /** 가운데가 (x, y) px인 작은 사각형(6×4 px) */
    // 테스트 도구: 가운데가 (x, y) px인 작은 사각형(6×4 px) 흠집 모양.
    private static List<List<Double>> box(double x, double y) {
        // 가운데 (x, y)에서 가로 ±3, 세로 ±2px인 네 꼭짓점을 돌려준다
        return List.of(List.of(x - 3, y - 2), List.of(x + 3, y - 2), List.of(x + 3, y + 2), List.of(x - 3, y + 2));
    }

    // [상황] 빠른 검사 세트는 다 올렸지만 부스 탐지 결과가 아직 안 왔다.
    // [기대] 등급을 매기지 않고 "올림 완료"(UPLOADED) 그대로이며 이벤트도 없다.
    @Test
    @DisplayName("빠른 검사 세트도 부스 탐지가 오기 전에는 등급을 매기지 않는다")
    void waitsForDetections() {
        // 준비: 다 올린 빠른 검사 세트를 만든다 (탐지 결과 없음)
        CaptureSet set = completedQuickSet();
        // 실행+확인: 등급을 매기면 "안 매김(false)"이어야 한다
        assertThat(grading.gradeIfReady(set)).isFalse();
        // 확인: 세트는 올림 완료(UPLOADED) 그대로여야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        // (도구) verify(가짜, never()).메서드(...) = 그 메서드가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 등급 이벤트가 나가지 않았어야 한다
        verify(events, never()).publishGraded(any());
    }

    // [상황] 탐지 결과는 왔지만 세트가 아직 완료되지 않았다(UPLOADING).
    // [기대] 등급을 매기지 않는다.
    @Test
    @DisplayName("완료 전(UPLOADING)인 빠른 검사 세트는 탐지가 와도 등급을 매기지 않는다")
    void waitsForCompletion() {
        // 준비: 빠른 검사 세트를 연다 (완료하지 않음 = UPLOADING)
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        // 준비: 탐지 결과는 받았다고 표시한다
        set.markDetectionsReceived();
        // 실행+확인: 등급을 매기면 "안 매김(false)"이어야 한다
        assertThat(grading.gradeIfReady(set)).isFalse();
        // 확인: 등급 이벤트가 나가지 않았어야 한다
        verify(events, never()).publishGraded(any());
    }

    // [상황] 같은 렌탈에서 앞서 등급 난 시점 수가 1, 2, 0개인 상태로 부스 빠른 검사 등급을 낸다.
    // [기대] 각각 수령(RCV), 반납(RET), 출고(OUT)로 보낸다(부스에서 찍었다고 출고로 고정하지 않음).
    @Test
    @DisplayName("부스 빠른 검사로 찍어도 앞서 등급 난 시점이 1개면 수령(RCV), 2개면 반납(RET)으로 보낸다")
    void boothQuickCanBeReceiveOrReturn() {
        // 실행+확인: 앞서 등급 난 시점이 1개면 수령(RCV)이어야 한다
        assertThat(kindAfter(1)).isEqualTo(CheckpointKind.RCV);
        // 실행+확인: 2개면 반납(RET)이어야 한다
        assertThat(kindAfter(2)).isEqualTo(CheckpointKind.RET);
        // 실행+확인: 0개면 출고(OUT)여야 한다
        assertThat(kindAfter(0)).isEqualTo(CheckpointKind.OUT);
    }

    // [상황] 앞 시점(출고) 빠른 검사는 흠집 0개로 A였다. 이번 시점에는 윗면 긁힘 1개와 바닥 오염 1개가 있다.
    // [기대] 근거에 "앞 시점 비교(개수만): 흠집 0개 → 1개(1개 늘어남, 오염 제외)"가 나란히 보인다.
    //        시점 종류는 수령(RCV), 등급은 이번 흠집만으로 정해 B다.
    @Test
    @DisplayName("앞 시점도 빠른 검사면 흠집 수를 '앞 시점 비교(개수만)' 근거로 나란히 보이고, 등급은 이번 흠집으로만 정한다")
    void quickComparesCountWithPrevious() {
        // 준비: 앞 시점(출고) 빠른 검사 세트를 만들고
        CaptureSet out = completedQuickSet("check-00000000000001");
        // 그 흠집은 0개라고 답하게 한다
        when(defects.findBySet(out)).thenReturn(List.of());
        // 준비: 앞 시점 등급 결과를 만들어
        GradeResult previous = new GradeResult(out.getCheckpointId());
        // A로 기록한다
        previous.recordGrade(out, Grade.A, "grade-rules-v1", List.of(), List.of());
        // 준비: 같은 렌탈의 앞 시점 등급을 물으면 위 결과를 돌려주게 한다
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.of(previous));
        // 준비: 앞서 등급 난 시점 수를 물으면 1(→ 수령)이라고 답하게 한다
        when(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(anyString(), anyString(), any()))
                .thenReturn(1L);

        // 준비: 이번 시점 빠른 검사 세트를 만들고
        CaptureSet ret = completedQuickSet("check-00000000000002");
        // 탐지 결과를 받았다고 표시한다
        ret.markDetectionsReceived();
        // 준비: 이번 흠집은 윗면 긁힘 1개, 바닥 오염 1개라고 답하게 한다
        when(defects.findBySet(ret)).thenReturn(List.of(
                Defect.fromBooth(photo(ret, Face.TOP, Camera.CAM1), DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(photo(ret, Face.BOT, Camera.CAM2), DefectClass.STAIN, BOX, 0.9, "v")));
        // 저장된 등급 결과를 붙잡을 도구
        ArgumentCaptor<GradeResult> saved = ArgumentCaptor.forClass(GradeResult.class);

        // 실행: 이번 시점에 등급을 매긴다
        grading.gradeIfReady(ret);

        // 확인: 등급 결과를 저장했어야 하고 붙잡는다
        verify(grades).save(saved.capture());
        // 확인: 근거에 개수 비교 문장(0개 → 1개, 오염 제외)이 있어야 한다
        assertThat(saved.getValue().getReasons()).contains("앞 시점 비교(개수만): 흠집 0개 → 1개(1개 늘어남, 오염 제외)");
        // 보낸 이벤트를 붙잡을 도구
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        // 확인: 이벤트를 보냈어야 하고 붙잡는다
        verify(events).publishGraded(sent.capture());
        // 확인: 시점 종류는 수령(RCV)이어야 한다
        assertThat(sent.getValue().checkpointType()).isEqualTo(CheckpointKind.RCV);
        // 확인: 등급은 이번 긁힘 1개로 정해 B여야 한다
        assertThat(sent.getValue().grade()).isEqualTo(Grade.B);
    }

    // [상황] 이미 등급이 있는 시점을 다시 찍어 재등급한다.
    // [기대] 시점 종류를 셀 때 "그 시점의 첫 등급 시각보다 먼저 등급 난 시점"만 센다(나중 시점이 생겨도 출고가 반납으로 바뀌지 않음).
    @Test
    @DisplayName("앞 시점을 다시 찍어 재등급해도 그 시점의 첫 등급 시각보다 먼저 등급 난 시점만 세어 종류가 바뀌지 않는다")
    void regradeKeepsKindByFirstGradeTime() {
        // 준비: 다 올린 빠른 검사 세트를 만들고
        CaptureSet set = completedQuickSet();
        // 탐지 결과를 받았다고 표시한다
        set.markDetectionsReceived();
        // 흠집은 0개라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of());
        // 준비: 이 시점의 기존 등급 결과를 만든다 (재등급 상황)
        GradeResult existing = new GradeResult(set.getCheckpointId());
        // 준비: 이 시점의 등급을 물으면 기존 결과를 돌려주게 한다
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(existing));

        // 실행: 다시 등급을 매긴다
        grading.gradeIfReady(set);

        // 확인: 시점 수를 셀 때 기존 결과의 첫 등급 시각(createdAt)을 기준으로 물었어야 한다
        verify(grades).countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(set.getRentalId(),
                set.getCheckpointId(), existing.getCreatedAt());
    }

    // 테스트 도구: 앞서 등급 난 시점 수를 정해 두고 빠른 검사 등급을 내서, 이벤트에 실린 시점 종류를 돌려준다.
    private CheckpointKind kindAfter(long earlier) {
        // 앞서 등급 난 시점 수를 물으면 earlier를 답하게 한다
        when(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(anyString(), anyString(), any()))
                .thenReturn(earlier);
        // 다 올린 빠른 검사 세트를 만들고
        CaptureSet set = completedQuickSet();
        // 탐지 결과를 받았다고 표시한다
        set.markDetectionsReceived();
        // 흠집은 0개라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of());
        // 새 가짜 발행기 (부를 때마다 따로 확인하려고)
        GradeEventPublisher publisher = mock(GradeEventPublisher.class);
        // 그 발행기로 등급 서비스를 만들어 등급을 매긴다
        new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, publisher).gradeIfReady(set);
        // 보낸 이벤트를 붙잡을 도구
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        // 이벤트를 보냈는지 확인하며 붙잡는다
        verify(publisher).publishGraded(sent.capture());
        // 이벤트의 시점 종류를 돌려준다
        return sent.getValue().checkpointType();
    }

    // 테스트 도구: 다 올린 부스 빠른 검사 세트(기본 시점 ID)를 만든다.
    private static CaptureSet completedQuickSet() {
        // 기본 시점 ID로 아래 도우미를 부른다
        return completedQuickSet("check-00000000000001");
    }

    // 테스트 도구: 다 올린 부스 빠른 검사 세트를 시점 ID를 정해 만든다.
    private static CaptureSet completedQuickSet(String checkpointId) {
        // 부스 빠른 검사 세트를 12장 목록으로 연다
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        // "올라온 사진" ID를 모을 빈 집합
        Set<String> present = new HashSet<>();
        // 모든 사진을 "올라옴"에 넣는다
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        // 세트를 완료한다
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        // 완료된 세트를 돌려준다
        return set;
    }

    // 테스트 도구: 세트에서 그 면·카메라의 L1 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera) {
        // 조명 L1로 아래 도우미를 부른다
        return photo(set, face, camera, Light.L1);
    }

    // 테스트 도구: 세트에서 그 면·카메라·조명의 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera, Light light) {
        // 세트 사진 중 그 면·카메라·조명인 첫 사진을 돌려준다 (없으면 예외)
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == camera && p.getLight() == light).findFirst()
                .orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 모든 사진에 같은 촬영 시각을 쓴다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
        // 바닥·윗면마다
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            // 조명 L1·L2·L3마다
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                // CAM1 사진 한 장
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                // CAM2 사진 한 장 → 2 × 3 × 2 = 12장
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        // 완성된 12장 목록을 돌려준다
        return specs;
    }
}
