// 이 파일이 확인하는 것: 카메라 사이 같은 흠집 합치기(11/9). 부스는 CAM1·CAM2 두 카메라로 같은 면을 찍으므로
// 한 흠집이 두 사진에 다 잡힌다. 실제 면 위 위치(mm)가 가까우면 하나로 합쳐 흠집 개수가 두 배로 세지지 않게 한다.
// 빠른 검사와 전체 검사가 함께 쓰는 규칙이다.

package com.gakdo.trace.capture.inspection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.comparison.domain.MatchRule;
import com.gakdo.trace.capture.comparison.domain.Point;
import com.gakdo.trace.capture.inspection.domain.DefectGroups.Group;
import com.gakdo.trace.capture.inspection.domain.DefectGroups.Placed;

/** 카메라 사이 같은 흠집 합치기(11/9). 빠른·전체 검사가 함께 쓴다. */
class CrossCameraMergeTest {

    // 테스트 도구: 한 카메라에서 잡힌 흠집 묶음을 면 위 위치(x, y mm)와 함께 만든다.
    private static Placed at(String id, Camera cam, DefectClass c, Double len, Boolean isNew, double x, double y) {
        // 바닥(BOT) 면의 흠집 묶음(종류·길이·새 흠집 여부·흠집 ID)에 잡은 카메라와 면 위 위치를 붙여 돌려준다
        return new Placed(new Group(Face.BOT, c, len, isNew, List.of(id)), Set.of(cam), new Point(x, y));
    }

    // [상황] CAM1의 긁힘(12mm)과 CAM2의 찌그러짐(20mm)이 약 9mm 떨어져 있고, 합치는 거리는 15mm다.
    // [기대] 하나로 합쳐지고, 더 심한 종류(찌그러짐)·더 긴 길이(20mm)·새 흠집 판정을 이어받는다. 두 흠집 ID를 모두 담는다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("CAM1·CAM2 묶음이 거리 안이면 하나로 합치고 가장 심한 종류·가장 긴 길이·신규 판정을 이어받는다")
    void mergesPairWithinDistance() {
        // 실행: 아래 두 묶음을 합치는 거리 15mm, 비교 규칙 V1로 합쳐 본다
        List<Group> out = DefectGroups.mergeAcrossCameras(List.of(
                // CAM1: 긁힘 12mm, 새 흠집, 위치 (100, 100)
                at("a", Camera.CAM1, DefectClass.SCRATCH, 12.0, true, 100, 100),
                // CAM2: 찌그러짐 20mm, 새 흠집, 위치 (108, 104) → 약 9mm 떨어짐
                at("b", Camera.CAM2, DefectClass.DENT, 20.0, true, 108, 104)), 15, MatchRule.V1);
        // (도구) assertThat(목록).hasSize(n) = 목록 개수가 n이어야 한다는 검사
        // 확인: 하나로 합쳐져 1개여야 한다
        assertThat(out).hasSize(1);
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 종류는 더 심한 찌그러짐이어야 한다
        assertThat(out.get(0).defectClass()).isEqualTo(DefectClass.DENT);
        // 확인: 길이는 더 긴 20mm여야 한다
        assertThat(out.get(0).lengthMm()).isEqualTo(20.0);
        // 확인: 새 흠집 판정을 이어받아야 한다
        assertThat(out.get(0).isNew()).isTrue();
        // 확인: 두 흠집 ID를 모두 담아야 한다
        assertThat(out.get(0).defectIds()).containsExactly("a", "b");
    }

    // [상황] ① 두 카메라 흠집이 30mm 떨어졌다(거리 20mm 밖) ② 같은 카메라의 두 흠집이 3mm 떨어졌다 ③ 한쪽 위치를 모른다.
    // [기대] 세 경우 모두 합치지 않는다(같은 카메라의 가까운 두 흠집은 진짜 두 개일 수 있음).
    @Test
    @DisplayName("거리 밖이거나 같은 카메라 묶음이거나 자리를 모르면 합치지 않는다")
    void keepsApart() {
        // 실행+확인: ① CAM1 (0,0)과 CAM2 (30,0)은 30mm라 거리 20mm 밖 → 2개로 남아야 한다
        assertThat(DefectGroups.mergeAcrossCameras(List.of(
                at("a", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                at("b", Camera.CAM2, DefectClass.SCRATCH, null, null, 30, 0)), 20, MatchRule.V1)).hasSize(2);
        // 실행+확인: ② 같은 CAM1의 두 흠집은 3mm여도 합치지 않아 2개여야 한다
        assertThat(DefectGroups.mergeAcrossCameras(List.of(
                at("a", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                at("b", Camera.CAM1, DefectClass.SCRATCH, null, null, 3, 0)), 20, MatchRule.V1)).hasSize(2);
        // 실행+확인: ③ CAM2 흠집의 위치를 모르면(null) 합치지 않아 2개여야 한다
        assertThat(DefectGroups.mergeAcrossCameras(List.of(
                at("a", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                new Placed(new Group(Face.BOT, DefectClass.SCRATCH, null, null, List.of("b")), Set.of(Camera.CAM2),
                        null)), 20, MatchRule.V1)).hasSize(2);
    }

    // [상황] CAM1 흠집 두 개(0mm, 16mm) 사이에 CAM2 흠집 하나(5mm)가 있다.
    // [기대] CAM2 흠집은 더 가까운 a1 하나와만 합쳐지고, a2는 따로 남는다(한 흠집을 두 번 쓰지 않음).
    @Test
    @DisplayName("한쪽 카메라의 두 흠집 사이에 다른 카메라 흠집이 있으면 더 가까운 쪽 하나와만 합친다")
    void oneToOne() {
        // 실행: CAM1 흠집 a1(0mm)·a2(16mm)와 그 사이 CAM2 흠집 b(5mm)를 거리 20mm로 합쳐 본다
        List<Group> out = DefectGroups.mergeAcrossCameras(List.of(
                at("a1", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                at("a2", Camera.CAM1, DefectClass.SCRATCH, null, null, 16, 0),
                at("b", Camera.CAM2, DefectClass.SCRATCH, null, null, 5, 0)), 20, MatchRule.V1);
        // 확인: 결과는 2개여야 한다
        assertThat(out).hasSize(2);
        // 확인: b는 더 가까운 a1과만 합쳐져야 한다
        assertThat(out.get(0).defectIds()).containsExactly("a1", "b");
        // 확인: a2는 따로 남아야 한다 (한 흠집을 두 번 쓰지 않음)
        assertThat(out.get(1).defectIds()).containsExactly("a2");
    }

    // [상황] 이미 두 카메라가 함께 잡은 묶음 두 개가 10mm 떨어져 있다.
    // [기대] 서로 다른 두 흠집이므로 합치지 않는다.
    @Test
    @DisplayName("이미 두 카메라가 함께 든 묶음끼리는 가까워도 합치지 않는다(서로 다른 두 흠집)")
    void bothCameraGroupsStayApart() {
        // 준비: 두 카메라가 이미 함께 잡은 묶음(a, b)을 위치 (0,0)에 만든다
        Placed one = new Placed(new Group(Face.BOT, DefectClass.SCRATCH, null, null, List.of("a", "b")),
                Set.of(Camera.CAM1, Camera.CAM2), new Point(0, 0));
        // 준비: 또 다른 두 카메라 묶음(c, d)을 위치 (10,0)에 만든다
        Placed two = new Placed(new Group(Face.BOT, DefectClass.SCRATCH, null, null, List.of("c", "d")),
                Set.of(Camera.CAM1, Camera.CAM2), new Point(10, 0));
        // 실행+확인: 10mm로 가까워도 합치지 않아 2개여야 한다
        assertThat(DefectGroups.mergeAcrossCameras(List.of(one, two), 15, MatchRule.V1)).hasSize(2);
    }

    // [상황] 실제 부스 사진(11/5~11/6, 11/9 바닥)에서 같은 흠집이 CAM1·CAM2 사진의 어디에 찍혔는지 다섯 쌍을,
    //        부스 보정 모서리 기준으로 면 위 mm 위치로 옮긴다.
    // [기대] 다섯 쌍 모두 두 카메라 위치가 2cm 안에서 만난다(합치기가 실제 부스에서도 통함).
    //        보정이 없는 면(앞 옆면 SF)이나 폰 사진은 위치를 계산하지 않는다.
    @Test
    @DisplayName("부스 보정 모서리로 옮긴 실제 부스 사진의 같은 흠집은 CAM1·CAM2 위치가 2cm 안에서 만난다")
    void boothFrameAgreesAcrossCameras() {
        // web Catalog.test.tsx와 같은 11/5~11/6 부스 사진 쌍 + 11/9 X391 바닥(사진 비율)
        // 준비: 실제 부스 사진에서 같은 흠집의 CAM1·CAM2 위치 다섯 쌍(사진 가로·세로 비율 0~1)
        double[][][] pairs = {
            {{0.375, 0.767}, {0.448, 0.736}},
            {{0.737, 0.738}, {0.808, 0.726}},
            {{0.717, 0.57}, {0.802, 0.573}},
            {{0.181, 0.551}, {0.242, 0.524}},
            {{0.661, 0.70}, {0.746, 0.689}},
        };
        // 실행: 쌍마다
        for (double[][] p : pairs) {
            // CAM1 위치를 1920×1080 픽셀로 바꾼 뒤 부스 보정으로 바닥 면 위 mm 위치로 옮긴다
            Point a = BoothFrame.faceMm(Face.BOT, Camera.CAM1, p[0][0] * 1920, p[0][1] * 1080, null).orElseThrow();
            // CAM2 위치도 같은 방법으로 mm 위치로 옮긴다
            Point b = BoothFrame.faceMm(Face.BOT, Camera.CAM2, p[1][0] * 1920, p[1][1] * 1080, null).orElseThrow();
            // 확인: 두 위치 사이 거리(피타고라스)가 20mm(2cm) 미만이어야 한다
            assertThat(Math.hypot(a.x() - b.x(), a.y() - b.y())).isLessThan(20);
        }
        // 확인: 보정이 없는 앞 옆면(SF)은 위치를 계산하지 않아 빈 값이어야 한다
        assertThat(BoothFrame.faceMm(Face.SF, Camera.CAM1, 100, 100, null)).isEmpty();
        // 확인: 폰 사진도 위치를 계산하지 않아 빈 값이어야 한다
        assertThat(BoothFrame.faceMm(Face.TOP, Camera.PHONE, 100, 100, null)).isEmpty();
    }
}
