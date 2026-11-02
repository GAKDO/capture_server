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

    @Test
    @DisplayName("코드 enum은 contracts codes.yaml inspection.defect_class·match_status 값과 같다")
    void codesMatchContract() {
        assertThat(Arrays.stream(DefectClass.values()).map(DefectClass::code))
                .containsExactly("scratch", "dent", "crack", "stain");
        assertThat(DefectClass.fromCode("crack")).isEqualTo(DefectClass.CRACK);
        assertThat(Arrays.stream(MatchStatus.values()).map(Enum::name)).containsExactly("NEW", "EXISTING");
    }

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

    @Test
    @DisplayName("다른 면의 흠집과는 짝짓지 않는다")
    void otherFaceNeverMatches() {
        ViewSnapshot before = snap(d("p1", Face.BOT, DefectClass.SCRATCH, box(100, 50, 30, 1)));
        ViewSnapshot now = snap(d("c1", Face.TOP, DefectClass.SCRATCH, box(100, 50, 30, 1)));

        assertThat(comparator.compare(before, now).matches())
                .containsExactly(new DefectMatch("c1", MatchStatus.NEW, null, null));
    }

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

    @Test
    @DisplayName("출고는 기준선이라 이전 시점이 없으면 짝을 만들지 않는다")
    void checkoutIsBaseline() {
        ComparisonResult r = comparator.compare(null, snap(d("c1", Face.SF, DefectClass.DENT, box(5, 5, 2, 2))));

        assertThat(r.ruleVersion()).isEqualTo("view-match-v1");
        assertThat(r.matches()).isEmpty();
        assertThat(r.unjudgedDefectIds()).isEmpty();
    }

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
        assertThatThrownBy(() -> DefectObservation.fromStandardPx("x", Face.TOP, DefectClass.DENT,
                List.of(List.of(1.0), List.of(1.0, 2.0), List.of(2.0, 2.0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("polygon 거리: 겹치면 0, 안에 들어 있으면 0, 떨어져 있으면 가장 가까운 거리")
    void gapGeometry() {
        assertThat(Shapes.gap(box(0, 0, 10, 10), box(5, 5, 10, 10))).isZero();
        assertThat(Shapes.gap(box(0, 0, 10, 10), box(2, 2, 1, 1))).isZero();
        assertThat(Shapes.gap(box(0, 0, 10, 10), box(13, 14, 2, 2))).isEqualTo(5.0);
    }

    @Test
    @DisplayName("규칙은 버전 이름(1~32자)과 0 이상 거리만 받고, 좌표는 유한하고 폴리곤은 꼭짓점 3개 이상이어야 한다")
    void validation() {
        assertThatThrownBy(() -> new MatchRule("", 5, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchRule("v", -1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchRule("v", Double.NaN, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchRule("v", 5, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d("x", Face.TOP, DefectClass.DENT, List.of(new Point(0, 0), new Point(1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Point(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ViewComparator(null)).isInstanceOf(NullPointerException.class);
    }

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

    static DefectObservation d(String id, Face face, DefectClass cls, List<Point> polygon) {
        return new DefectObservation(id, face, cls, polygon);
    }

    static ViewSnapshot snap(DefectObservation... defects) {
        return new ViewSnapshot(List.of(defects), ALL);
    }

    /** 왼쪽 위 (x, y), 가로 w, 세로 h 직사각형. */
    static List<Point> box(double x, double y, double w, double h) {
        return List.of(new Point(x, y), new Point(x + w, y), new Point(x + w, y + h), new Point(x, y + h));
    }
}
