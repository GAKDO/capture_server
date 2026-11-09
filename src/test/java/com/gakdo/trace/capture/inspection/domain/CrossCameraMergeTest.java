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

    private static Placed at(String id, Camera cam, DefectClass c, Double len, Boolean isNew, double x, double y) {
        return new Placed(new Group(Face.BOT, c, len, isNew, List.of(id)), Set.of(cam), new Point(x, y));
    }

    @Test
    @DisplayName("CAM1·CAM2 묶음이 거리 안이면 하나로 합치고 가장 심한 종류·가장 긴 길이·신규 판정을 이어받는다")
    void mergesPairWithinDistance() {
        List<Group> out = DefectGroups.mergeAcrossCameras(List.of(
                at("a", Camera.CAM1, DefectClass.SCRATCH, 12.0, true, 100, 100),
                at("b", Camera.CAM2, DefectClass.DENT, 20.0, true, 108, 104)), 15, MatchRule.V1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).defectClass()).isEqualTo(DefectClass.DENT);
        assertThat(out.get(0).lengthMm()).isEqualTo(20.0);
        assertThat(out.get(0).isNew()).isTrue();
        assertThat(out.get(0).defectIds()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("거리 밖이거나 같은 카메라 묶음이거나 자리를 모르면 합치지 않는다")
    void keepsApart() {
        assertThat(DefectGroups.mergeAcrossCameras(List.of(
                at("a", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                at("b", Camera.CAM2, DefectClass.SCRATCH, null, null, 30, 0)), 20, MatchRule.V1)).hasSize(2);
        assertThat(DefectGroups.mergeAcrossCameras(List.of(
                at("a", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                at("b", Camera.CAM1, DefectClass.SCRATCH, null, null, 3, 0)), 20, MatchRule.V1)).hasSize(2);
        assertThat(DefectGroups.mergeAcrossCameras(List.of(
                at("a", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                new Placed(new Group(Face.BOT, DefectClass.SCRATCH, null, null, List.of("b")), Set.of(Camera.CAM2),
                        null)), 20, MatchRule.V1)).hasSize(2);
    }

    @Test
    @DisplayName("한쪽 카메라의 두 흠집 사이에 다른 카메라 흠집이 있으면 더 가까운 쪽 하나와만 합친다")
    void oneToOne() {
        List<Group> out = DefectGroups.mergeAcrossCameras(List.of(
                at("a1", Camera.CAM1, DefectClass.SCRATCH, null, null, 0, 0),
                at("a2", Camera.CAM1, DefectClass.SCRATCH, null, null, 16, 0),
                at("b", Camera.CAM2, DefectClass.SCRATCH, null, null, 5, 0)), 20, MatchRule.V1);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).defectIds()).containsExactly("a1", "b");
        assertThat(out.get(1).defectIds()).containsExactly("a2");
    }

    @Test
    @DisplayName("이미 두 카메라가 함께 든 묶음끼리는 가까워도 합치지 않는다(서로 다른 두 흠집)")
    void bothCameraGroupsStayApart() {
        Placed one = new Placed(new Group(Face.BOT, DefectClass.SCRATCH, null, null, List.of("a", "b")),
                Set.of(Camera.CAM1, Camera.CAM2), new Point(0, 0));
        Placed two = new Placed(new Group(Face.BOT, DefectClass.SCRATCH, null, null, List.of("c", "d")),
                Set.of(Camera.CAM1, Camera.CAM2), new Point(10, 0));
        assertThat(DefectGroups.mergeAcrossCameras(List.of(one, two), 15, MatchRule.V1)).hasSize(2);
    }

    @Test
    @DisplayName("부스 보정 모서리로 옮긴 실제 부스 사진의 같은 흠집은 CAM1·CAM2 위치가 2cm 안에서 만난다")
    void boothFrameAgreesAcrossCameras() {
        // web Catalog.test.tsx와 같은 11/5~11/6 부스 사진 쌍 + 11/9 X391 바닥(사진 비율)
        double[][][] pairs = {
            {{0.375, 0.767}, {0.448, 0.736}},
            {{0.737, 0.738}, {0.808, 0.726}},
            {{0.717, 0.57}, {0.802, 0.573}},
            {{0.181, 0.551}, {0.242, 0.524}},
            {{0.661, 0.70}, {0.746, 0.689}},
        };
        for (double[][] p : pairs) {
            Point a = BoothFrame.faceMm(Face.BOT, Camera.CAM1, p[0][0] * 1920, p[0][1] * 1080, null).orElseThrow();
            Point b = BoothFrame.faceMm(Face.BOT, Camera.CAM2, p[1][0] * 1920, p[1][1] * 1080, null).orElseThrow();
            assertThat(Math.hypot(a.x() - b.x(), a.y() - b.y())).isLessThan(20);
        }
        assertThat(BoothFrame.faceMm(Face.SF, Camera.CAM1, 100, 100, null)).isEmpty();
        assertThat(BoothFrame.faceMm(Face.TOP, Camera.PHONE, 100, 100, null)).isEmpty();
    }
}
