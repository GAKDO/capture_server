package com.gakdo.trace.capture.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.comparison.domain.DefectObservation;
import com.gakdo.trace.capture.comparison.domain.MatchAccuracy;
import com.gakdo.trace.capture.comparison.domain.MatchAccuracy.Report;
import com.gakdo.trace.capture.comparison.domain.MatchAccuracy.Truth;
import com.gakdo.trace.capture.comparison.domain.MatchRule;
import com.gakdo.trace.capture.comparison.domain.Point;
import com.gakdo.trace.capture.comparison.domain.Shapes;
import com.gakdo.trace.capture.comparison.domain.ViewComparator;
import com.gakdo.trace.capture.comparison.domain.ViewSnapshot;

/**
 * 테스트 흠집 판정 정확도(FR-INS-03). 정답을 아는 가짜 흠집을 고정 시드로 만들어, 정합 오차·부분 탐지·중복 탐지·종류 오분류를 넣고 잰다.
 * 결과는 target/view-match-accuracy.txt에 남고, specs/002-view-comparison/accuracy.md에 옮겨 적는다.
 * 실제 기기 테스트 흠집이 생기면 같은 MatchAccuracy.evaluate로 잰다.
 */
class TestScratchAccuracyTest {

    private static final int SCENARIOS = 300;
    private static final double FACE_W = 300;
    private static final double FACE_H = 210;

    /** 조건: 이름, 최대 이동(mm), 최대 회전(°), 신규 흠집과 기존 흠집 사이 최소 거리(mm). */
    record Condition(String name, double shiftMm, double rotateDeg, double newGapMm) {
    }

    @Test
    @DisplayName("테스트 흠집 판정 정확도를 기록한다: 보통 정합 오차(±2 mm, ±0.5°)에서 신규·기존 판정 97% 이상, 억울한 신규 1% 이하")
    void recordsAccuracy() throws IOException {
        List<Condition> conditions = List.of(
                new Condition("보통(±2mm·±0.5°, 신규는 8mm 밖)", 2, 0.5, 8),
                new Condition("정합 나쁨(±4mm·±1°, 신규는 8mm 밖)", 4, 1, 8),
                new Condition("신규가 가까움(±2mm·±0.5°, 신규는 3~8mm)", 2, 0.5, 3));
        StringBuilder out = new StringBuilder("규칙 " + MatchRule.V1 + ", 시나리오 " + SCENARIOS + "개씩, 시드 42\n");
        Map<String, Report> reports = new HashMap<>();
        for (Condition c : conditions) {
            Report r = run(c, new Random(42));
            reports.put(c.name(), r);
            out.append("- ").append(c.name()).append(" → ").append(r.summary()).append('\n');
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/view-match-accuracy.txt"), out);
        System.out.print(out);

        Report base = reports.get(conditions.get(0).name());
        assertThat(base.statusAccuracy()).isGreaterThanOrEqualTo(0.97);
        assertThat(base.accuracy()).isGreaterThanOrEqualTo(0.95);
        assertThat((double) base.falseNew() / base.total()).isLessThanOrEqualTo(0.01);
    }

    private static Report run(Condition c, Random rnd) {
        ViewComparator comparator = new ViewComparator(MatchRule.V1);
        List<Report> reports = new ArrayList<>();
        for (int s = 0; s < SCENARIOS; s++) {
            List<List<Point>> truthOld = new ArrayList<>();
            for (int i = 0, n = 1 + rnd.nextInt(5); i < n; i++) {
                truthOld.add(scratch(rnd, 10 + rnd.nextDouble() * 50));
            }
            List<DefectObservation> before = new ArrayList<>();
            Map<Integer, List<String>> prevIds = new HashMap<>();
            for (int i = 0; i < truthOld.size(); i++) {
                for (int k = 0, dup = 1 + rnd.nextInt(2); k < dup; k++) {   // CAM1·CAM2 중복 탐지
                    String id = "p" + s + "-" + i + "-" + k;
                    before.add(obs(id, DefectClass.SCRATCH, jitter(truthOld.get(i), rnd)));
                    prevIds.computeIfAbsent(i, x -> new ArrayList<>()).add(id);
                }
            }
            double dx = (rnd.nextDouble() * 2 - 1) * c.shiftMm();
            double dy = (rnd.nextDouble() * 2 - 1) * c.shiftMm();
            double th = Math.toRadians((rnd.nextDouble() * 2 - 1) * c.rotateDeg());
            List<DefectObservation> now = new ArrayList<>();
            Map<String, Truth> truths = new HashMap<>();
            for (int i = 0; i < truthOld.size(); i++) {
                if (rnd.nextDouble() < 0.1) {
                    continue;                                       // 이번엔 탐지 못 함
                }
                List<Point> p = rnd.nextDouble() < 0.3 ? partial(truthOld.get(i), rnd) : truthOld.get(i);
                DefectClass cls = rnd.nextDouble() < 0.1 ? DefectClass.CRACK : DefectClass.SCRATCH;
                String id = "c" + s + "-" + i;
                now.add(obs(id, cls, jitter(misalign(p, dx, dy, th), rnd)));
                truths.put(id, Truth.existing(prevIds.get(i)));
            }
            for (int j = 0, n = rnd.nextInt(3); j < n; j++) {
                List<Point> fresh = placeNew(truthOld, c.newGapMm(), rnd);
                if (fresh != null) {
                    String id = "n" + s + "-" + j;
                    now.add(obs(id, DefectClass.SCRATCH, jitter(misalign(fresh, dx, dy, th), rnd)));
                    truths.put(id, Truth.isNew());
                }
            }
            reports.add(MatchAccuracy.evaluate(comparator.compare(
                    new ViewSnapshot(before, EnumSet.allOf(Face.class)), new ViewSnapshot(now, EnumSet.allOf(Face.class))),
                    truths));
        }
        return MatchAccuracy.sum(MatchRule.V1.version(), reports);
    }

    /** 실제 위치 기준으로 기존 흠집과 newGapMm(보통 조건은 8mm, 가까움 조건은 3~8mm) 떨어진 새 흠집. */
    private static List<Point> placeNew(List<List<Point>> old, double minGap, Random rnd) {
        double maxGap = minGap < 8 ? 8 : Double.MAX_VALUE;
        for (int t = 0; t < 200; t++) {
            List<Point> cand = scratch(rnd, 10 + rnd.nextDouble() * 30);
            double g = old.stream().mapToDouble(o -> Shapes.gap(cand, o)).min().orElse(Double.MAX_VALUE);
            if (g >= minGap && g < maxGap) {
                return cand;
            }
        }
        return null;
    }

    /** 폭 1 mm, 임의 방향의 긁힘(가는 직사각형). */
    private static List<Point> scratch(Random rnd, double length) {
        double cx = 30 + rnd.nextDouble() * (FACE_W - 60);
        double cy = 30 + rnd.nextDouble() * (FACE_H - 60);
        double a = rnd.nextDouble() * Math.PI;
        double ux = Math.cos(a) * length / 2;
        double uy = Math.sin(a) * length / 2;
        double vx = -Math.sin(a) * 0.5;
        double vy = Math.cos(a) * 0.5;
        return List.of(new Point(cx - ux - vx, cy - uy - vy), new Point(cx + ux - vx, cy + uy - vy),
                new Point(cx + ux + vx, cy + uy + vy), new Point(cx - ux + vx, cy - uy + vy));
    }

    /** 길이의 50~100%만 탐지(앞쪽 두 꼭짓점을 안쪽으로 당김). */
    private static List<Point> partial(List<Point> s, Random rnd) {
        double keep = 0.5 + rnd.nextDouble() * 0.5;
        Point a = lerp(s.get(0), s.get(1), keep);
        Point b = lerp(s.get(3), s.get(2), keep);
        return List.of(s.get(0), a, b, s.get(3));
    }

    /** 면 가운데를 중심으로 회전 th, 이동 (dx, dy): 시점 사이 정합 오차. */
    private static List<Point> misalign(List<Point> s, double dx, double dy, double th) {
        double ox = FACE_W / 2;
        double oy = FACE_H / 2;
        return s.stream().map(p -> new Point(
                ox + (p.x() - ox) * Math.cos(th) - (p.y() - oy) * Math.sin(th) + dx,
                oy + (p.x() - ox) * Math.sin(th) + (p.y() - oy) * Math.cos(th) + dy)).toList();
    }

    /** 꼭짓점마다 ±0.5 mm 탐지 흔들림. */
    private static List<Point> jitter(List<Point> s, Random rnd) {
        return s.stream().map(p -> new Point(p.x() + rnd.nextDouble() - 0.5, p.y() + rnd.nextDouble() - 0.5)).toList();
    }

    private static Point lerp(Point a, Point b, double t) {
        return new Point(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }

    private static DefectObservation obs(String id, DefectClass cls, List<Point> polygon) {
        return new DefectObservation(id, Face.TOP, cls, polygon);
    }
}
