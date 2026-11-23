// 이 파일이 확인하는 것: 시점 비교 규칙 v1(view-match-v1). 지금 시점(예: 반납)의 흠집마다 이전 시점(예: 출고)에도 있던 것인지(EXISTING),
// 새로 생긴 것인지(NEW)를 펼친 면 위 위치(mm)로 판정한다. 새 흠집만 정산 대상이 되므로 고객 차감에 바로 영향이 있다.
// 좌표는 모두 mm이고, 규칙 거리 안의 같은 면 흠집이면 "원래 있던 것"으로 본다.

package com.gakdo.trace.capture.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.ComparisonResult;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.comparison.domain.DefectMatch;
import com.gakdo.trace.capture.comparison.domain.DefectObservation;
import com.gakdo.trace.capture.comparison.domain.MatchAccuracy;
import com.gakdo.trace.capture.comparison.domain.MatchRule;
import com.gakdo.trace.capture.comparison.domain.MatchStatus;
import com.gakdo.trace.capture.comparison.domain.Point;
import com.gakdo.trace.capture.comparison.domain.Shapes;
import com.gakdo.trace.capture.comparison.domain.ViewComparator;
import com.gakdo.trace.capture.comparison.domain.ViewSnapshot;

/** 시점 비교 규칙 v1 단위 시험. 좌표는 mm. */
class ViewComparatorTest {

    private static final Set<Face> ALL = EnumSet.allOf(Face.class);
    private final ViewComparator comparator = new ViewComparator(MatchRule.V1);

    // [상황] 서버의 흠집 종류·비교 결과 코드를 계약과 비교한다.
    // [기대] 종류는 codes.yaml과 같은 scratch(긁힘)·dent(찍힘)·crack(깨짐)·stain(오염), 결과는 NEW·EXISTING이다.
    @Test
    @DisplayName("코드 enum은 contracts codes.yaml inspection.defect_class·match_status 값과 같다")
    void codesMatchContract() {
        assertThat(Arrays.stream(DefectClass.values()).map(DefectClass::code))
                .containsExactly("scratch", "dent", "crack", "stain");
        assertThat(DefectClass.fromCode("crack")).isEqualTo(DefectClass.CRACK);
        assertThat(Arrays.stream(MatchStatus.values()).map(Enum::name)).containsExactly("NEW", "EXISTING");
    }

    // [상황] 출고 때 윗면 긁힘 하나가 있고, 반납 때 1mm 떨어진 긁힘과 9mm 떨어진 긁힘이 있다.
    // [기대] 1mm 것은 원래 있던 흠집(짝 ID와 거리 1mm), 9mm 것은 새 흠집(가장 가까운 거리 9mm)이다. 규칙 버전도 남는다.
    @Test
    @DisplayName("규칙 거리 안의 같은 면 흠집은 기존(짝 ID·거리), 밖이면 신규(가장 가까운 거리)")
    void withinGapIsExisting() {
        ViewSnapshot before = snap(d("p1", Face.TOP, DefectClass.SCRATCH, box(100, 50, 30, 1)));
        ViewSnapshot now = snap(
                d("c1", Face.TOP, DefectClass.SCRATCH, box(103, 52, 30, 1)),
                d("c2", Face.TOP, DefectClass.SCRATCH, box(100, 60, 30, 1)));

        ComparisonResult r = comparator.compare(before, now);

        assertThat(r.ruleVersion()).isEqualTo("view-match-v1");
        assertThat(r.matches()).containsExactly(
                new DefectMatch("c1", MatchStatus.EXISTING, "p1", 1.0),
                new DefectMatch("c2", MatchStatus.NEW, null, 9.0));
    }

    // [상황] 출고 때 바닥에 있던 흠집과 같은 좌표에 반납 때 윗면 흠집이 있다.
    // [기대] 다른 면이므로 짝짓지 않고 새 흠집으로 본다.
    @Test
    @DisplayName("다른 면의 흠집과는 짝짓지 않는다")
    void otherFaceNeverMatches() {
        ViewSnapshot before = snap(d("p1", Face.BOT, DefectClass.SCRATCH, box(100, 50, 30, 1)));
        ViewSnapshot now = snap(d("c1", Face.TOP, DefectClass.SCRATCH, box(100, 50, 30, 1)));

        assertThat(comparator.compare(before, now).matches())
                .containsExactly(new DefectMatch("c1", MatchStatus.NEW, null, null));
    }

    // [상황] 출고 때 왼쪽 옆면에 60mm 긴 긁힘이 있었고, 반납 때는 그 일부(15mm)와 다른 일부(20mm)로 나뉘어 잡혔다.
    // [기대] 둘 다 같은 출고 흠집과 짝지어 원래 있던 것으로 본다(탐지가 끊겨 잡혀도 억울한 차감 없음).
    @Test
    @DisplayName("긴 긁힘의 일부만 다시 탐지되거나 둘로 나뉘어도 같은 이전 흠집과 짝짓는다")
    void partialAndSplitDetections() {
        ViewSnapshot before = snap(d("p1", Face.SL, DefectClass.SCRATCH, box(20, 20, 60, 1)));
        ViewSnapshot now = snap(
                d("c1", Face.SL, DefectClass.SCRATCH, box(20, 20, 15, 1)),
                d("c2", Face.SL, DefectClass.SCRATCH, box(55, 20.5, 20, 1)));

        assertThat(comparator.compare(before, now).matches())
                .extracting(DefectMatch::prevDefectId).containsExactly("p1", "p1");
    }

    // [상황] 출고: 윗면 깨짐·긁힘, 키보드 오염. 반납: 윗면 긁힘·찍힘, 키보드 긁힘·오염이 거의 같은 자리에 있다.
    // [기대] 같은 종류 후보를 먼저 골라 긁힘은 긁힘과, 찍힘은 (같은 종류가 없으니) 깨짐과 짝짓는다(AI가 종류를 바꿔 봐도 같은 흠집).
    //        오염은 오염끼리만 짝지으므로, 키보드 긁힘은 오염 자리에 있어도 새 흠집이고 키보드 오염은 원래 있던 것이다.
    @Test
    @DisplayName("종류가 바뀌어도(긁힘→균열) 짝짓되 같은 종류 후보를 먼저 고르고, 오염은 오염끼리만 짝짓는다")
    void classRules() {
        ViewSnapshot before = snap(
                d("crack", Face.TOP, DefectClass.CRACK, box(100, 50, 30, 1)),
                d("scr", Face.TOP, DefectClass.SCRATCH, box(100, 53, 30, 1)),
                d("stain", Face.KB, DefectClass.STAIN, box(10, 10, 5, 5)));
        ViewSnapshot now = snap(
                d("c1", Face.TOP, DefectClass.SCRATCH, box(100, 51.5, 30, 1)),
                d("c2", Face.TOP, DefectClass.DENT, box(100, 50, 30, 1)),
                d("c3", Face.KB, DefectClass.SCRATCH, box(10, 10, 5, 5)),
                d("c4", Face.KB, DefectClass.STAIN, box(11, 11, 3, 3)));

        assertThat(comparator.compare(before, now).matches()).containsExactly(
                new DefectMatch("c1", MatchStatus.EXISTING, "scr", 0.5),
                new DefectMatch("c2", MatchStatus.EXISTING, "crack", 0.0),
                new DefectMatch("c3", MatchStatus.NEW, null, 0.0),
                new DefectMatch("c4", MatchStatus.EXISTING, "stain", 0.0));
    }

    // [상황] 출고 때 바닥은 판정 불가였고, 반납 때 흰 화면(SCR1)은 판정 불가다. 반납 흠집은 윗면·바닥·SCR1에 하나씩 있다.
    // [기대] 윗면 흠집만 비교하고, 바닥·SCR1 흠집은 "비교 못 함" 목록으로 따로 돌려준다(한쪽 사진이 없으면 새것이라 단정하지 않음).
    @Test
    @DisplayName("지금이나 이전 시점에 판정 불가인 면의 흠집은 비교하지 않고 따로 돌려준다")
    void unjudgeableFacesAreSkipped() {
        ViewSnapshot before = new ViewSnapshot(List.of(d("p1", Face.TOP, DefectClass.SCRATCH, box(0, 0, 9, 1))),
                EnumSet.complementOf(EnumSet.of(Face.BOT)));
        ViewSnapshot now = new ViewSnapshot(List.of(
                d("c1", Face.TOP, DefectClass.SCRATCH, box(0, 0, 9, 1)),
                d("c2", Face.BOT, DefectClass.SCRATCH, box(0, 0, 9, 1)),
                d("c3", Face.SCR1, DefectClass.SCRATCH, box(0, 0, 9, 1))),
                EnumSet.complementOf(EnumSet.of(Face.SCR1)));

        ComparisonResult r = comparator.compare(before, now);

        assertThat(r.matches()).extracting(DefectMatch::defectId).containsExactly("c1");
        assertThat(r.unjudgedDefectIds()).containsExactly("c2", "c3");
    }

    // [상황] 이전 시점 없이(출고) 비교한다.
    // [기대] 출고는 기준선이라 짝도, 비교 못 함 목록도 만들지 않는다.
    @Test
    @DisplayName("출고는 기준선이라 이전 시점이 없으면 짝을 만들지 않는다")
    void checkoutIsBaseline() {
        ComparisonResult r = comparator.compare(null, snap(d("c1", Face.SF, DefectClass.DENT, box(5, 5, 2, 2))));

        assertThat(r.ruleVersion()).isEqualTo("view-match-v1");
        assertThat(r.matches()).isEmpty();
        assertThat(r.unjudgedDefectIds()).isEmpty();
    }

    // [상황] DB에 저장된 표준 좌표 px 흠집을 비교한다. 이전 흠집과 정확히 20px(5mm) 떨어진 것과 21px(5.25mm) 떨어진 것이 있다.
    // [기대] px를 mm로 바꿔(40px → 10mm) 비교해 5mm 것은 원래 있던 것, 5.25mm 것은 새 흠집이다. 좌표가 깨진 꼭짓점은 거절한다.
    @Test
    @DisplayName("DB의 표준 좌표 px polygon은 mm로 바꿔 비교한다: 20 px = 5 mm 안이면 기존")
    void standardPxIsConvertedToMm() {
        DefectObservation prev = DefectObservation.fromStandardPx("p1", Face.TOP, DefectClass.SCRATCH,
                List.of(List.of(0.0, 0.0), List.of(40.0, 0.0), List.of(40.0, 4.0), List.of(0.0, 4.0)));
        DefectObservation near = DefectObservation.fromStandardPx("c1", Face.TOP, DefectClass.SCRATCH,
                List.of(List.of(0.0, 24.0), List.of(40.0, 24.0), List.of(40.0, 28.0), List.of(0.0, 28.0)));
        DefectObservation far = DefectObservation.fromStandardPx("c2", Face.TOP, DefectClass.SCRATCH,
                List.of(List.of(0.0, 25.0), List.of(40.0, 25.0), List.of(40.0, 29.0), List.of(0.0, 29.0)));

        assertThat(prev.polygon().get(1)).isEqualTo(new Point(10, 0));
        assertThat(comparator.compare(snap(prev), snap(near, far)).matches()).containsExactly(
                new DefectMatch("c1", MatchStatus.EXISTING, "p1", 5.0),
                new DefectMatch("c2", MatchStatus.NEW, null, 5.25));
        List<List<Double>> shortVertex = List.of(List.of(1.0), List.of(1.0, 2.0), List.of(2.0, 2.0));
        assertThatThrownBy(() -> DefectObservation.fromStandardPx("x", Face.TOP, DefectClass.DENT, shortVertex))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // [상황] 두 흠집 모양 사이 거리를 잰다: 겹침 / 한쪽이 안에 들어감 / 떨어짐.
    // [기대] 겹치거나 안에 있으면 0, 떨어져 있으면 가장 가까운 거리(5mm)다.
    @Test
    @DisplayName("polygon 거리: 겹치면 0, 안에 들어 있으면 0, 떨어져 있으면 가장 가까운 거리")
    void gapGeometry() {
        assertThat(Shapes.gap(box(0, 0, 10, 10), box(5, 5, 10, 10))).isZero();
        assertThat(Shapes.gap(box(0, 0, 10, 10), box(2, 2, 1, 1))).isZero();
        assertThat(Shapes.gap(box(0, 0, 10, 10), box(13, 14, 2, 2))).isEqualTo(5.0);
    }

    // [상황] 규칙 이름이 비었거나, 거리가 음수·NaN·무한대이거나, 꼭짓점이 2개뿐이거나, 좌표가 NaN이거나, 규칙 없이 비교기를 만든다.
    // [기대] 모두 거절한다(잘못된 값으로 판정하지 않음).
    @Test
    @DisplayName("규칙은 버전 이름(1~32자)과 0 이상 거리만 받고, 좌표는 유한하고 폴리곤은 꼭짓점 3개 이상이어야 한다")
    void validation() {
        assertThatThrownBy(() -> new MatchRule("", 5, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchRule("v", -1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchRule("v", Double.NaN, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchRule("v", 5, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        List<Point> twoPoints = List.of(new Point(0, 0), new Point(1, 1));
        assertThatThrownBy(() -> d("x", Face.TOP, DefectClass.DENT, twoPoints))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Point(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ViewComparator(null)).isInstanceOf(NullPointerException.class);
    }

    // [상황] 정답을 아는 5개 흠집의 비교 결과를 채점한다: 맞음 1, 놓친 신규(새것인데 기존으로 봄) 1, 억울한 신규(기존인데 새것으로 봄) 1, 짝 틀림 1, 결과에서 빠짐 1.
    // [기대] 다섯 경우를 각각 1개씩 따로 세고, 짝까지 맞음 20%, 신규·기존 판정 40%로 요약한다.
    @Test
    @DisplayName("정확도: 억울한 신규·놓친 신규·짝 틀림·빠짐을 따로 센다")
    void accuracyCounts() {
        ComparisonResult r = new ComparisonResult("view-match-v1", List.of(
                new DefectMatch("ok", MatchStatus.EXISTING, "p2", 0.0),
                new DefectMatch("fn", MatchStatus.NEW, null, 7.0),
                new DefectMatch("fe", MatchStatus.EXISTING, "p1", 1.0),
                new DefectMatch("wp", MatchStatus.EXISTING, "p9", 1.0)), List.of());
        Map<String, MatchAccuracy.Truth> truth = Map.of(
                "ok", MatchAccuracy.Truth.existing(List.of("p1", "p2")),
                "fn", MatchAccuracy.Truth.existing(List.of("p3")),
                "fe", MatchAccuracy.Truth.isNew(),
                "wp", MatchAccuracy.Truth.existing(List.of("p4")),
                "gone", MatchAccuracy.Truth.isNew());

        MatchAccuracy.Report rep = MatchAccuracy.evaluate(r, truth);

        assertThat(rep).isEqualTo(new MatchAccuracy.Report("view-match-v1", 5, 1, 1, 1, 1, 1));
        assertThat(rep.accuracy()).isEqualTo(0.2);
        assertThat(rep.statusAccuracy()).isEqualTo(0.4);
        assertThat(rep.summary()).startsWith("view-match-v1: 신규·기존 판정 40.0%, 짝까지 맞음 20.0% (1/5)");
    }

    // 테스트 도구: 흠집 관측 하나(ID, 면, 종류, 모양)를 만든다.
    static DefectObservation d(String id, Face face, DefectClass cls, List<Point> polygon) {
        return new DefectObservation(id, face, cls, polygon);
    }

    // 테스트 도구: 모든 면이 판정 가능한 시점 흠집 묶음을 만든다.
    static ViewSnapshot snap(DefectObservation... defects) {
        return new ViewSnapshot(List.of(defects), ALL);
    }

    /** 왼쪽 위 (x, y), 가로 w, 세로 h 직사각형. */
    // 테스트 도구: 왼쪽 위 (x, y), 가로 w, 세로 h mm 직사각형 흠집 모양.
    static List<Point> box(double x, double y, double w, double h) {
        return List.of(new Point(x, y), new Point(x + w, y), new Point(x + w, y + h), new Point(x, y + h));
    }
}
