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

    // 표준 좌표 (0,0)~(40,40) 정사각형 흠집 모양 (크기 재기 시험용)
    private static final List<List<Double>> SQUARE = List.of(List.of(0.0, 0.0), List.of(40.0, 0.0),
            List.of(40.0, 40.0), List.of(0.0, 40.0));

    // 테스트 도구: 숫자 9개로 3×3 행렬을 만든다.
    private static List<List<Double>> rows(double... v) {
        // 숫자 9개를 3개씩 세 줄로 묶어 돌려준다
        return List.of(List.of(v[0], v[1], v[2]), List.of(v[3], v[4], v[5]), List.of(v[6], v[7], v[8]));
    }

    // [상황] 2배 확대 + (10, 20) 이동하는 변환으로 원본 사진의 점 세 개를 옮긴다.
    // [기대] 표준 좌표에서 기대한 위치(예: (1,1) → (12,22))로 정확히 옮겨진다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("원본 사진 px는 사진의 호모그래피로 표준 좌표 px로 옮겨진다")
    void homographyMapsPhotoPxToStandardPx() {
        // 준비: 가로·세로 2배 키우고 (10, 20) 옮기는 변환을 만든다
        Homography h = Homography.of(rows(2, 0, 10, 0, 2, 20, 0, 0, 1));
        // (도구) assertThat(목록).containsExactly(...) = 목록에 정확히 이 값들만 이 순서로 있어야 한다는 검사
        // 실행+확인: 점 (1,1)·(5,0)·(0,5)를 옮기면 (12,22)·(20,20)·(10,30)이 돼야 한다
        assertThat(h.map(List.of(List.of(1.0, 1.0), List.of(5.0, 0.0), List.of(0.0, 5.0))))
                .containsExactly(List.of(12.0, 22.0), List.of(20.0, 20.0), List.of(10.0, 30.0));
    }

    // [상황] 원근(비스듬히 찍힌 사진)이 있는 변환으로 점을 옮긴 뒤 역변환으로 되돌린다.
    // [기대] 원래 점과 0.05px 안으로 같다.
    @Test
    @DisplayName("원근 호모그래피는 w로 나누고, 역행렬로 되돌리면 원래 점이 된다")
    void inverseRoundTrips() {
        // 준비: 비스듬히 찍힌 것 같은 원근 변환을 만든다 (마지막 줄 0.0004, 0.0002가 원근)
        Homography h = Homography.of(rows(1.2, 0.1, 30, -0.05, 0.9, 12, 0.0004, 0.0002, 1));
        // 준비: 원본 사진 위 점 세 개
        List<List<Double>> photo = List.of(List.of(100.0, 200.0), List.of(800.0, 210.0), List.of(450.0, 900.0));
        // 실행: 변환으로 옮긴 뒤 역변환으로 되돌린다
        List<List<Double>> back = h.inverse().map(h.map(photo));
        // 점마다
        for (int i = 0; i < photo.size(); i++) {
            // (도구) isCloseTo(기대값, offset(오차)) = 기대값과 오차 범위 안으로 같아야 한다는 검사 (소수 계산은 아주 조금 어긋나므로)
            // 확인: 되돌린 x가 원래 x와 0.05px 안으로 같아야 한다
            assertThat(back.get(i).get(0)).isCloseTo(photo.get(i).get(0), org.assertj.core.data.Offset.offset(0.05));
            // 확인: 되돌린 y도 0.05px 안으로 같아야 한다
            assertThat(back.get(i).get(1)).isCloseTo(photo.get(i).get(1), org.assertj.core.data.Offset.offset(0.05));
        }
    }

    // [상황] recon-worker가 3×3이 아닌 행렬, 역행렬이 없는 행렬, NaN이 든 행렬을 보낸다.
    // [기대] 정합 결과로 받지 않는다(잘못된 위치로 흠집을 옮기지 않음).
    @Test
    @DisplayName("3×3이 아니거나 역행렬이 없는 호모그래피는 정합 결과로 받지 않는다")
    void invalidHomographyIsRejected() {
        // 준비: 3×3이 아닌 2×2 행렬
        List<List<Double>> notSquare = List.of(List.of(1.0, 0.0), List.of(0.0, 1.0));
        // 준비: 둘째 줄이 첫째 줄의 2배라 역행렬이 없는 행렬
        List<List<Double>> singular = rows(1, 2, 3, 2, 4, 6, 0, 0, 1);
        // 준비: 숫자가 아닌 값(NaN)이 든 행렬
        List<List<Double>> notFinite = rows(1, 0, 0, 0, Double.NaN, 0, 0, 0, 1);
        // (도구) assertThatThrownBy(() -> 코드).isInstanceOf(종류) = 그 코드를 실행하면 이 종류의 예외가 나야 한다는 검사
        // 실행+확인: 2×2 행렬로 변환을 만들면 잘못된 값 예외가 나야 한다
        assertThatThrownBy(() -> Homography.of(notSquare)).isInstanceOf(IllegalArgumentException.class);
        // 실행+확인: 역행렬 없는 행렬도 예외가 나야 한다
        assertThatThrownBy(() -> Homography.of(singular)).isInstanceOf(IllegalArgumentException.class);
        // 실행+확인: NaN이 든 행렬도 예외가 나야 한다
        assertThatThrownBy(() -> Homography.of(notFinite)).isInstanceOf(IllegalArgumentException.class);
    }

    // [상황] 표준 좌표에서 40×40px 정사각형 흠집을 잰다.
    // [기대] 길이(대각선)는 14.14mm, 넓이는 100mm²다(1px = 0.25mm).
    @Test
    @DisplayName("길이·넓이는 표준 좌표에서 1 px = 0.25 mm로 잰다")
    void measuresInMillimetres() {
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 실행+확인: 40×40px 정사각형의 길이(대각선)는 14.14mm여야 한다
        assertThat(PolygonMeasure.lengthMm(SQUARE)).isEqualTo(14.14);   // 대각선 56.57 px
        // 실행+확인: 넓이는 100mm²여야 한다
        assertThat(PolygonMeasure.areaMm2(SQUARE)).isEqualTo(100.0);    // 1600 px² × 0.0625
    }

    // [상황] 윗면은 CAM1 조명 하나만 실패, 바닥은 CAM2 사진이 전부 실패, 앞 옆면(SF)은 품질 미달이다.
    // [기대] 판정 불가는 바닥(이유에 CAM2)과 SF이고, 판정 가능은 윗면뿐이다.
    //        면을 찍은 카메라마다 쓸 수 있는 사진이 하나는 있어야 한다(한 카메라 시야가 통째로 비면 놓치는 곳이 생김).
    @Test
    @DisplayName("면을 찍은 카메라마다 정합·품질 통과 사진이 있어야 판정 가능하고, 한 카메라가 모두 실패하면 판정 불가다")
    void faceNeedsAUsablePhotoPerCamera() {
        // PhotoState(면, 카메라, 올라옴, 품질 통과, 정합 성공): 뒤의 true/false 세 개가 이 순서다.
        // 셋 다 true여야 판정에 쓸 수 있는 사진이다(정합 = 사진 속 면을 반듯하게 펼치는 것).
        // 준비: 면·카메라별 사진 상태 6개를 만든다
        List<PhotoState> photos = List.of(
                // 윗면 CAM1: 모두 통과
                new PhotoState(Face.TOP, Camera.CAM1, true, true, true),
                // 윗면 CAM1 다른 조명: 정합 실패
                new PhotoState(Face.TOP, Camera.CAM1, true, true, false),   // 조명 하나 실패는 괜찮다
                // 윗면 CAM2: 모두 통과
                new PhotoState(Face.TOP, Camera.CAM2, true, true, true),
                // 바닥 CAM1: 모두 통과
                new PhotoState(Face.BOT, Camera.CAM1, true, true, true),
                // 바닥 CAM2: 정합 실패 (CAM2 사진은 이것뿐)
                new PhotoState(Face.BOT, Camera.CAM2, true, true, false),   // CAM2 뒤 반이 비었다
                // 앞 옆면 CAM1: 품질 미달
                new PhotoState(Face.SF, Camera.CAM1, true, false, true));   // 품질 미달
        // 실행: 건너뛴 면 없이 판정 불가 면과 이유를 구한다
        Map<Face, String> out = Judgeability.unjudgeable(photos, Map.of());
        // (도구) containsOnlyKeys(...) = 맵의 키가 딱 이것들뿐이어야 한다는 검사
        // 확인: 판정 불가 면은 바닥과 앞 옆면뿐이어야 한다
        assertThat(out).containsOnlyKeys(Face.BOT, Face.SF);
        // (도구) contains(글자) = 문장에 그 글자가 들어 있어야 한다는 검사
        // 확인: 바닥의 이유에 CAM2가 적혀야 한다 (어느 카메라를 다시 볼지 알려 주려고)
        assertThat(out.get(Face.BOT)).contains("CAM2");
        // 실행+확인: 판정 가능 면은 윗면 하나뿐이어야 한다
        assertThat(Judgeability.judgeable(photos, out)).containsExactly(Face.TOP);
    }

    // [상황] 선택 면인 흰 화면(SCR1)을 "화면 꺼짐"으로 건너뛰었다.
    // [기대] SCR1은 "건너뜀(화면 꺼짐)"으로 판정 불가이고, 찍은 윗면은 판정 불가가 아니다.
    @Test
    @DisplayName("건너뛴 선택 면은 사유와 함께 판정 불가다")
    void skippedFaceIsUnjudgeable() {
        // 실행: 폰 윗면 사진 1장, SCR1을 "화면 꺼짐"으로 건너뛴 상태로 판정 불가 면을 구한다
        Map<Face, String> out = Judgeability.unjudgeable(
                // 폰으로 찍은 윗면 1장: 올라옴·품질 통과·정합 성공
                List.of(new PhotoState(Face.TOP, Camera.PHONE, true, true, true)), Map.of(Face.SCR1, "화면 꺼짐"));
        // (도구) containsEntry(키, 값) = 맵에 그 키가 그 값으로 있어야 함. doesNotContainKey(키) = 그 키가 없어야 함
        // 확인: SCR1은 "건너뜀(화면 꺼짐)"으로 있고, 윗면은 판정 불가에 없어야 한다
        assertThat(out).containsEntry(Face.SCR1, "건너뜀(화면 꺼짐)").doesNotContainKey(Face.TOP);
    }

    // 테스트 도구: 표준 좌표 x 위치에 가로 40px 긁힘 하나(길이·신규 여부 포함)를 만든다.
    private static Item item(String id, Face face, DefectClass c, double x, Double len, MatchStatus st) {
        // x 위치에서 가로 40px·세로 4px짜리 가는 네모를 만든다
        List<List<Double>> px = List.of(List.of(x, 100.0), List.of(x + 40, 100.0), List.of(x + 40, 104.0),
                List.of(x, 104.0));
        // 표준 좌표 관측에 길이와 신규 여부를 붙여 묶기 항목으로 돌려준다
        return new Item(DefectObservation.fromStandardPx(id, face, c, px), len, st);
    }

    // 테스트 도구: 흠집 ID를 번호로 만든다.
    private static String id(int n) {
        // "defect" 뒤에 번호를 15자리로 붙인 21자 ID를 돌려준다
        return String.format("defect%015d", n);
    }

    // [상황] 윗면에 겹치는 긁힘 두 개(카메라·조명별 중복 탐지), 멀리 떨어진 긁힘 하나, 바닥 긁힘 하나가 있다.
    // [기대] 3개로 센다. 겹친 둘은 하나로 묶이고 더 긴 길이(12mm)와 신규 판정을 이어받는다.
    //        멀리 있는 것은 기존 흠집, 바닥 것은 비교 전(판정 없음) 그대로다.
    @Test
    @DisplayName("같은 면에서 5 mm 안의 같은 흠집(카메라·조명별 중복 탐지)은 하나로 센다")
    void duplicateDetectionsAreOneDefect() {
        // 실행: 아래 흠집 4개를 비교 규칙 V1로 묶는다
        List<Group> groups = DefectGroups.group(List.of(
                // 윗면 x=100 긁힘 10mm, 신규
                item(id(1), Face.TOP, DefectClass.SCRATCH, 100, 10.0, MatchStatus.NEW),
                // 윗면 x=110 긁힘 12mm, 신규 (1번과 겹침)
                item(id(2), Face.TOP, DefectClass.SCRATCH, 110, 12.0, MatchStatus.NEW),   // 겹침
                // 윗면 x=400 긁힘 8mm, 기존 (멀리 떨어짐)
                item(id(3), Face.TOP, DefectClass.SCRATCH, 400, 8.0, MatchStatus.EXISTING),
                // 바닥 x=100 긁힘 9mm, 비교 전(판정 없음)
                item(id(4), Face.BOT, DefectClass.SCRATCH, 100, 9.0, null)), MatchRule.V1);
        // (도구) assertThat(목록).hasSize(n) = 목록 개수가 n이어야 한다는 검사
        // 확인: 묶음은 3개여야 한다
        assertThat(groups).hasSize(3);
        // 첫 묶음을 꺼낸다
        Group first = groups.get(0);
        // 확인: 첫 묶음에는 겹친 1·2번이 함께 들어 있어야 한다
        assertThat(first.defectIds()).containsExactly(id(1), id(2));
        // 확인: 길이는 더 긴 12mm여야 한다
        assertThat(first.lengthMm()).isEqualTo(12.0);
        // (도구) isTrue() / isFalse() / isNull() = 값이 참 / 거짓 / 비어 있음(null)이어야 한다는 검사
        // 확인: 신규 판정을 이어받아야 한다
        assertThat(first.isNew()).isTrue();
        // 확인: 멀리 있던 것은 기존 흠집이어야 한다
        assertThat(groups.get(1).isNew()).isFalse();
        // 확인: 바닥 것은 판정 없음(null) 그대로여야 한다
        assertThat(groups.get(2).isNew()).isNull();
    }

    // [상황] 흠집이 없을 때와 새 긁힘 12mm 하나가 있을 때 등급을 매긴다.
    // [기대] 각각 A와 B이고, 규칙 버전 grade-rules-v1과 근거 "신규 긁힘 1개(12mm)"가 남는다.
    @Test
    @DisplayName("흠집이 없으면 A, 짧은 긁힘 1~4개면 B다")
    void gradeAandB() {
        // 실행+확인: 흠집 없는 출고 시점(앞 시점 없음)의 등급은 A여야 한다
        assertThat(GradeRules.evaluate(List.of(), Map.of(), true).grade()).isEqualTo(Grade.A);
        // 실행: 새 긁힘 12mm 하나로 (앞 시점이 있는 상태에서) 등급을 매긴다
        GradeRules.Outcome b = GradeRules.evaluate(List.of(group(DefectClass.SCRATCH, 12.0, true)), Map.of(), false);
        // 확인: 등급은 B여야 한다
        assertThat(b.grade()).isEqualTo(Grade.B);
        // 확인: 어떤 규칙으로 매겼는지 버전이 남아야 한다
        assertThat(b.ruleVersion()).isEqualTo("grade-rules-v1");
        // 확인: 근거 문장에 "신규 긁힘 1개(12mm)"가 있어야 한다
        assertThat(b.reasons()).contains("신규 긁힘 1개(12mm)");
    }

    // [상황] 찍힘 1개 / 31mm 긁힘 / 짧은 긁힘 5개 / 깨짐(균열) 1개 / 찍힘 3개로 각각 등급을 매긴다.
    // [기대] 앞의 세 경우는 C, 깨짐과 찍힘 3개는 D다.
    @Test
    @DisplayName("찍힘이나 30 mm 이상 긁힘·긁힘 5개 이상이면 C, 균열이나 찍힘 3개 이상이면 D다")
    void gradeCandD() {
        // 실행+확인: 찍힘 1개면 C여야 한다
        assertThat(GradeRules.evaluate(List.of(group(DefectClass.DENT, null, null)), Map.of(), true).grade())
                .isEqualTo(Grade.C);
        // 실행+확인: 31mm 긁힘이면 C여야 한다
        assertThat(GradeRules.evaluate(List.of(group(DefectClass.SCRATCH, 31.0, false)), Map.of(), false).grade())
                .isEqualTo(Grade.C);
        // 준비: 3mm 짧은 긁힘 5개
        List<Group> five = List.of(group(DefectClass.SCRATCH, 3.0, false), group(DefectClass.SCRATCH, 3.0, false),
                group(DefectClass.SCRATCH, 3.0, false), group(DefectClass.SCRATCH, 3.0, false),
                group(DefectClass.SCRATCH, 3.0, false));
        // 실행+확인: 짧은 긁힘 5개면 C여야 한다
        assertThat(GradeRules.evaluate(five, Map.of(), false).grade()).isEqualTo(Grade.C);
        // 실행+확인: 깨짐 1개면 D여야 한다
        assertThat(GradeRules.evaluate(List.of(group(DefectClass.CRACK, 5.0, true)), Map.of(), false).grade())
                .isEqualTo(Grade.D);
        // 준비: 찍힘 3개
        List<Group> dents = List.of(group(DefectClass.DENT, null, null), group(DefectClass.DENT, null, null),
                group(DefectClass.DENT, null, null));
        // 실행+확인: 찍힘 3개면 D여야 한다
        assertThat(GradeRules.evaluate(dents, Map.of(), true).grade()).isEqualTo(Grade.D);
    }

    // [상황] 50mm 오염 하나가 있고 흰 화면(SCR1)은 건너뛰어 판정 불가다.
    // [기대] 오염은 등급에서 빼서 A이고, 근거에 "오염 1개는 등급에서 뺌"과 "판정 불가 면 SCR1: 건너뜀(화면 꺼짐)"이 남는다.
    @Test
    @DisplayName("오염(stain)은 등급에서 빼고, 판정 불가 면은 근거 문장에 남는다")
    void stainIsExcludedAndUnjudgeableIsReported() {
        // 실행: 50mm 오염 하나, SCR1 판정 불가로 등급을 매긴다
        GradeRules.Outcome out = GradeRules.evaluate(List.of(group(DefectClass.STAIN, 50.0, true)),
                Map.of(Face.SCR1, "건너뜀(화면 꺼짐)"), false);
        // 확인: 오염은 빼므로 A여야 한다
        assertThat(out.grade()).isEqualTo(Grade.A);
        // 확인: 근거에 "오염 1개는 등급에서 뺌"과 판정 불가 면 문장이 있어야 한다
        assertThat(out.reasons()).contains("오염 1개는 등급에서 뺌", "판정 불가 면 SCR1: 건너뜀(화면 꺼짐)");
    }

    // [상황] 같은 렌탈에서 앞서 등급이 난 시점 수가 0, 1, 2, 3개다.
    // [기대] 0개면 출고(OUT), 1개면 수령(RCV), 2개 이상이면 반납(RET)이다(부스·폰 어디서 찍었는지와 무관).
    @Test
    @DisplayName("시점 종류: 같은 렌탈에서 앞서 등급 난 시점이 0개면 출고, 1개면 수령, 2개 이상이면 반납(촬영 출처와 무관)")
    void checkpointKind() {
        // 실행+확인: 앞서 등급 난 시점이 0개면 출고(OUT)여야 한다
        assertThat(CheckpointKind.ofPosition(0)).isEqualTo(CheckpointKind.OUT);
        // 실행+확인: 1개면 수령(RCV)이어야 한다
        assertThat(CheckpointKind.ofPosition(1)).isEqualTo(CheckpointKind.RCV);
        // 실행+확인: 2개면 반납(RET)이어야 한다
        assertThat(CheckpointKind.ofPosition(2)).isEqualTo(CheckpointKind.RET);
        // 실행+확인: 3개여도 반납(RET)이어야 한다
        assertThat(CheckpointKind.ofPosition(3)).isEqualTo(CheckpointKind.RET);
    }

    // 테스트 도구: 윗면 흠집 묶음 하나(종류·길이·신규 여부)를 만든다.
    private static Group group(DefectClass c, Double len, Boolean isNew) {
        // 윗면에 받은 종류·길이·신규 여부로 묶음 하나(흠집 ID 9번)를 만들어 돌려준다
        return new Group(Face.TOP, c, len, isNew, List.of(id(9)));
    }
}
