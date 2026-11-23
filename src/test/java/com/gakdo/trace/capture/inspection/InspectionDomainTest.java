// 이 파일이 확인하는 것: 검사·등급의 핵심 계산 규칙(specs/003-inspection-grading). DB 없이 도는 순수 계산 시험이다.
// - 면 맞추기(호모그래피): 원본 사진 위 흠집 위치를 펼친 면의 표준 좌표로 옮기기
// - 크기 재기: 표준 좌표 1px = 0.25mm
// - 판정 가능 여부: 면마다 쓸 수 있는 사진이 있는지
// - 중복 탐지 묶기, 등급 A~D 규칙(grade-rules-v1), 시점 종류(출고·수령·반납) 정하기

package com.gakdo.trace.capture.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.comparison.domain.DefectObservation;
import com.gakdo.trace.capture.comparison.domain.MatchRule;
import com.gakdo.trace.capture.comparison.domain.MatchStatus;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.DefectGroups;
import com.gakdo.trace.capture.inspection.domain.DefectGroups.Group;
import com.gakdo.trace.capture.inspection.domain.DefectGroups.Item;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.GradeRules;
import com.gakdo.trace.capture.inspection.domain.Homography;
import com.gakdo.trace.capture.inspection.domain.Judgeability;
import com.gakdo.trace.capture.inspection.domain.Judgeability.PhotoState;
import com.gakdo.trace.capture.inspection.domain.PolygonMeasure;

/** CA2 A 몫의 순수 도메인(Docker 없이). 시험 이름은 수용 기준 문장(specs/003-inspection-grading). */
class InspectionDomainTest {

    private static final List<List<Double>> SQUARE = List.of(List.of(0.0, 0.0), List.of(40.0, 0.0),
            List.of(40.0, 40.0), List.of(0.0, 40.0));

    // 테스트 도구: 숫자 9개로 3×3 행렬을 만든다.
    private static List<List<Double>> rows(double... v) {
        return List.of(List.of(v[0], v[1], v[2]), List.of(v[3], v[4], v[5]), List.of(v[6], v[7], v[8]));
    }

    // [상황] 2배 확대 + (10, 20) 이동하는 변환으로 원본 사진의 점 세 개를 옮긴다.
    // [기대] 표준 좌표에서 기대한 위치(예: (1,1) → (12,22))로 정확히 옮겨진다.
    @Test
    @DisplayName("원본 사진 px는 사진의 호모그래피로 표준 좌표 px로 옮겨진다")
    void homographyMapsPhotoPxToStandardPx() {
        Homography h = Homography.of(rows(2, 0, 10, 0, 2, 20, 0, 0, 1));
        assertThat(h.map(List.of(List.of(1.0, 1.0), List.of(5.0, 0.0), List.of(0.0, 5.0))))
                .containsExactly(List.of(12.0, 22.0), List.of(20.0, 20.0), List.of(10.0, 30.0));
    }

    // [상황] 원근(비스듬히 찍힌 사진)이 있는 변환으로 점을 옮긴 뒤 역변환으로 되돌린다.
    // [기대] 원래 점과 0.05px 안으로 같다.
    @Test
    @DisplayName("원근 호모그래피는 w로 나누고, 역행렬로 되돌리면 원래 점이 된다")
    void inverseRoundTrips() {
        Homography h = Homography.of(rows(1.2, 0.1, 30, -0.05, 0.9, 12, 0.0004, 0.0002, 1));
        List<List<Double>> photo = List.of(List.of(100.0, 200.0), List.of(800.0, 210.0), List.of(450.0, 900.0));
        List<List<Double>> back = h.inverse().map(h.map(photo));
        for (int i = 0; i < photo.size(); i++) {
            assertThat(back.get(i).get(0)).isCloseTo(photo.get(i).get(0), org.assertj.core.data.Offset.offset(0.05));
            assertThat(back.get(i).get(1)).isCloseTo(photo.get(i).get(1), org.assertj.core.data.Offset.offset(0.05));
        }
    }

    // [상황] recon-worker가 3×3이 아닌 행렬, 역행렬이 없는 행렬, NaN이 든 행렬을 보낸다.
    // [기대] 정합 결과로 받지 않는다(잘못된 위치로 흠집을 옮기지 않음).
    @Test
    @DisplayName("3×3이 아니거나 역행렬이 없는 호모그래피는 정합 결과로 받지 않는다")
    void invalidHomographyIsRejected() {
        List<List<Double>> notSquare = List.of(List.of(1.0, 0.0), List.of(0.0, 1.0));
        List<List<Double>> singular = rows(1, 2, 3, 2, 4, 6, 0, 0, 1);
        List<List<Double>> notFinite = rows(1, 0, 0, 0, Double.NaN, 0, 0, 0, 1);
        assertThatThrownBy(() -> Homography.of(notSquare)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Homography.of(singular)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Homography.of(notFinite)).isInstanceOf(IllegalArgumentException.class);
    }

    // [상황] 표준 좌표에서 40×40px 정사각형 흠집을 잰다.
    // [기대] 길이(대각선)는 14.14mm, 넓이는 100mm²다(1px = 0.25mm).
    @Test
    @DisplayName("길이·넓이는 표준 좌표에서 1 px = 0.25 mm로 잰다")
    void measuresInMillimetres() {
        assertThat(PolygonMeasure.lengthMm(SQUARE)).isEqualTo(14.14);   // 대각선 56.57 px
        assertThat(PolygonMeasure.areaMm2(SQUARE)).isEqualTo(100.0);    // 1600 px² × 0.0625
    }

    // [상황] 윗면은 CAM1 조명 하나만 실패, 바닥은 CAM2 사진이 전부 실패, 앞 옆면(SF)은 품질 미달이다.
    // [기대] 판정 불가는 바닥(이유에 CAM2)과 SF이고, 판정 가능은 윗면뿐이다.
    //        면을 찍은 카메라마다 쓸 수 있는 사진이 하나는 있어야 한다(한 카메라 시야가 통째로 비면 놓치는 곳이 생김).
    @Test
    @DisplayName("면을 찍은 카메라마다 정합·품질 통과 사진이 있어야 판정 가능하고, 한 카메라가 모두 실패하면 판정 불가다")
    void faceNeedsAUsablePhotoPerCamera() {
        List<PhotoState> photos = List.of(
                new PhotoState(Face.TOP, Camera.CAM1, true, true, true),
                new PhotoState(Face.TOP, Camera.CAM1, true, true, false),   // 조명 하나 실패는 괜찮다
                new PhotoState(Face.TOP, Camera.CAM2, true, true, true),
                new PhotoState(Face.BOT, Camera.CAM1, true, true, true),
                new PhotoState(Face.BOT, Camera.CAM2, true, true, false),   // CAM2 뒤 반이 비었다
                new PhotoState(Face.SF, Camera.CAM1, true, false, true));   // 품질 미달
        Map<Face, String> out = Judgeability.unjudgeable(photos, Map.of());
        assertThat(out).containsOnlyKeys(Face.BOT, Face.SF);
        assertThat(out.get(Face.BOT)).contains("CAM2");
        assertThat(Judgeability.judgeable(photos, out)).containsExactly(Face.TOP);
    }

    // [상황] 선택 면인 흰 화면(SCR1)을 "화면 꺼짐"으로 건너뛰었다.
    // [기대] SCR1은 "건너뜀(화면 꺼짐)"으로 판정 불가이고, 찍은 윗면은 판정 불가가 아니다.
    @Test
    @DisplayName("건너뛴 선택 면은 사유와 함께 판정 불가다")
    void skippedFaceIsUnjudgeable() {
        Map<Face, String> out = Judgeability.unjudgeable(
                List.of(new PhotoState(Face.TOP, Camera.PHONE, true, true, true)), Map.of(Face.SCR1, "화면 꺼짐"));
        assertThat(out).containsEntry(Face.SCR1, "건너뜀(화면 꺼짐)").doesNotContainKey(Face.TOP);
    }

    // 테스트 도구: 표준 좌표 x 위치에 가로 40px 긁힘 하나(길이·신규 여부 포함)를 만든다.
    private static Item item(String id, Face face, DefectClass c, double x, Double len, MatchStatus st) {
        List<List<Double>> px = List.of(List.of(x, 100.0), List.of(x + 40, 100.0), List.of(x + 40, 104.0),
                List.of(x, 104.0));
        return new Item(DefectObservation.fromStandardPx(id, face, c, px), len, st);
    }

    // 테스트 도구: 흠집 ID를 번호로 만든다.
    private static String id(int n) {
        return String.format("defect%015d", n);
    }

    // [상황] 윗면에 겹치는 긁힘 두 개(카메라·조명별 중복 탐지), 멀리 떨어진 긁힘 하나, 바닥 긁힘 하나가 있다.
    // [기대] 3개로 센다. 겹친 둘은 하나로 묶이고 더 긴 길이(12mm)와 신규 판정을 이어받는다.
    //        멀리 있는 것은 기존 흠집, 바닥 것은 비교 전(판정 없음) 그대로다.
    @Test
    @DisplayName("같은 면에서 5 mm 안의 같은 흠집(카메라·조명별 중복 탐지)은 하나로 센다")
    void duplicateDetectionsAreOneDefect() {
        List<Group> groups = DefectGroups.group(List.of(
                item(id(1), Face.TOP, DefectClass.SCRATCH, 100, 10.0, MatchStatus.NEW),
                item(id(2), Face.TOP, DefectClass.SCRATCH, 110, 12.0, MatchStatus.NEW),   // 겹침
                item(id(3), Face.TOP, DefectClass.SCRATCH, 400, 8.0, MatchStatus.EXISTING),
                item(id(4), Face.BOT, DefectClass.SCRATCH, 100, 9.0, null)), MatchRule.V1);
        assertThat(groups).hasSize(3);
        Group first = groups.get(0);
        assertThat(first.defectIds()).containsExactly(id(1), id(2));
        assertThat(first.lengthMm()).isEqualTo(12.0);
        assertThat(first.isNew()).isTrue();
        assertThat(groups.get(1).isNew()).isFalse();
        assertThat(groups.get(2).isNew()).isNull();
    }

    // [상황] 흠집이 없을 때와 새 긁힘 12mm 하나가 있을 때 등급을 매긴다.
    // [기대] 각각 A와 B이고, 규칙 버전 grade-rules-v1과 근거 "신규 긁힘 1개(12mm)"가 남는다.
    @Test
    @DisplayName("흠집이 없으면 A, 짧은 긁힘 1~4개면 B다")
    void gradeAandB() {
        assertThat(GradeRules.evaluate(List.of(), Map.of(), true).grade()).isEqualTo(Grade.A);
        GradeRules.Outcome b = GradeRules.evaluate(List.of(group(DefectClass.SCRATCH, 12.0, true)), Map.of(), false);
        assertThat(b.grade()).isEqualTo(Grade.B);
        assertThat(b.ruleVersion()).isEqualTo("grade-rules-v1");
        assertThat(b.reasons()).contains("신규 긁힘 1개(12mm)");
    }

    // [상황] 찍힘 1개 / 31mm 긁힘 / 짧은 긁힘 5개 / 깨짐(균열) 1개 / 찍힘 3개로 각각 등급을 매긴다.
    // [기대] 앞의 세 경우는 C, 깨짐과 찍힘 3개는 D다.
    @Test
    @DisplayName("찍힘이나 30 mm 이상 긁힘·긁힘 5개 이상이면 C, 균열이나 찍힘 3개 이상이면 D다")
    void gradeCandD() {
        assertThat(GradeRules.evaluate(List.of(group(DefectClass.DENT, null, null)), Map.of(), true).grade())
                .isEqualTo(Grade.C);
        assertThat(GradeRules.evaluate(List.of(group(DefectClass.SCRATCH, 31.0, false)), Map.of(), false).grade())
                .isEqualTo(Grade.C);
        List<Group> five = List.of(group(DefectClass.SCRATCH, 3.0, false), group(DefectClass.SCRATCH, 3.0, false),
                group(DefectClass.SCRATCH, 3.0, false), group(DefectClass.SCRATCH, 3.0, false),
                group(DefectClass.SCRATCH, 3.0, false));
        assertThat(GradeRules.evaluate(five, Map.of(), false).grade()).isEqualTo(Grade.C);
        assertThat(GradeRules.evaluate(List.of(group(DefectClass.CRACK, 5.0, true)), Map.of(), false).grade())
                .isEqualTo(Grade.D);
        List<Group> dents = List.of(group(DefectClass.DENT, null, null), group(DefectClass.DENT, null, null),
                group(DefectClass.DENT, null, null));
        assertThat(GradeRules.evaluate(dents, Map.of(), true).grade()).isEqualTo(Grade.D);
    }

    // [상황] 50mm 오염 하나가 있고 흰 화면(SCR1)은 건너뛰어 판정 불가다.
    // [기대] 오염은 등급에서 빼서 A이고, 근거에 "오염 1개는 등급에서 뺌"과 "판정 불가 면 SCR1: 건너뜀(화면 꺼짐)"이 남는다.
    @Test
    @DisplayName("오염(stain)은 등급에서 빼고, 판정 불가 면은 근거 문장에 남는다")
    void stainIsExcludedAndUnjudgeableIsReported() {
        GradeRules.Outcome out = GradeRules.evaluate(List.of(group(DefectClass.STAIN, 50.0, true)),
                Map.of(Face.SCR1, "건너뜀(화면 꺼짐)"), false);
        assertThat(out.grade()).isEqualTo(Grade.A);
        assertThat(out.reasons()).contains("오염 1개는 등급에서 뺌", "판정 불가 면 SCR1: 건너뜀(화면 꺼짐)");
    }

    // [상황] 같은 렌탈에서 앞서 등급이 난 시점 수가 0, 1, 2, 3개다.
    // [기대] 0개면 출고(OUT), 1개면 수령(RCV), 2개 이상이면 반납(RET)이다(부스·폰 어디서 찍었는지와 무관).
    @Test
    @DisplayName("시점 종류: 같은 렌탈에서 앞서 등급 난 시점이 0개면 출고, 1개면 수령, 2개 이상이면 반납(촬영 출처와 무관)")
    void checkpointKind() {
        assertThat(CheckpointKind.ofPosition(0)).isEqualTo(CheckpointKind.OUT);
        assertThat(CheckpointKind.ofPosition(1)).isEqualTo(CheckpointKind.RCV);
        assertThat(CheckpointKind.ofPosition(2)).isEqualTo(CheckpointKind.RET);
        assertThat(CheckpointKind.ofPosition(3)).isEqualTo(CheckpointKind.RET);
    }

    // 테스트 도구: 윗면 흠집 묶음 하나(종류·길이·신규 여부)를 만든다.
    private static Group group(DefectClass c, Double len, Boolean isNew) {
        return new Group(Face.TOP, c, len, isNew, List.of(id(9)));
    }
}
