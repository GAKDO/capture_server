// 이 파일이 확인하는 것: 시점 비교(출고 vs 반납)에서 "새 흠집인지, 원래 있던 흠집인지" 판정이 얼마나 정확한지(FR-INS-03).
// 정답을 아는 가짜 흠집을 고정 시드(42)로 300개 상황씩 만들고, 실제에서 생기는 어려움을 일부러 넣는다:
// 두 시점 사이 면 맞추기 오차(이동·회전), 흠집 일부만 탐지, CAM1·CAM2 중복 탐지, 종류 오분류, 이번엔 못 잡은 흠집.
// 결과는 target/view-match-accuracy.txt에 남고 specs/002-view-comparison/accuracy.md에 옮겨 적는다.

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

    // 조건마다 만들 상황 수 (300개)
    private static final int SCENARIOS = 300;
    // 면 가로 300mm (노트북 윗면 크기 정도)
    private static final double FACE_W = 300;
    // 면 세로 210mm
    private static final double FACE_H = 210;

    /** 조건: 이름, 최대 이동(mm), 최대 회전(°), 신규 흠집과 기존 흠집 사이 최소 거리(mm). */
    // 테스트 도구: 시험 조건(이름, 최대 이동 mm, 최대 회전 °, 새 흠집이 기존 흠집과 떨어진 최소 거리 mm).
    record Condition(String name, double shiftMm, double rotateDeg, double newGapMm) {
    }

    // [상황] 세 조건으로 정확도를 잰다: 보통(±2mm·±0.5°), 정합 나쁨(±4mm·±1°), 새 흠집이 기존 흠집 가까이(3~8mm) 생김.
    // [기대] 보통 조건에서 신규·기존 판정 정확도 97% 이상, 전체 정확도 95% 이상,
    //        원래 있던 흠집을 새 흠집으로 잘못 봐서 고객이 억울하게 차감당하는 경우가 1% 이하다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("테스트 흠집 판정 정확도를 기록한다: 보통 정합 오차(±2 mm, ±0.5°)에서 신규·기존 판정 97% 이상, 억울한 신규 1% 이하")
    void recordsAccuracy() throws IOException {
        // 준비: 시험할 세 조건(보통, 정합 나쁨, 신규가 가까움)을 만든다
        List<Condition> conditions = List.of(
                new Condition("보통(±2mm·±0.5°, 신규는 8mm 밖)", 2, 0.5, 8),
                new Condition("정합 나쁨(±4mm·±1°, 신규는 8mm 밖)", 4, 1, 8),
                new Condition("신규가 가까움(±2mm·±0.5°, 신규는 3~8mm)", 2, 0.5, 3));
        // 준비: 결과 보고서 첫 줄(규칙 버전, 상황 수, 시드)을 적기 시작한다
        StringBuilder out = new StringBuilder("규칙 " + MatchRule.V1 + ", 시나리오 " + SCENARIOS + "개씩, 시드 42\n");
        // 준비: 조건 이름별 결과를 담을 빈 맵
        Map<String, Report> reports = new HashMap<>();
        // 조건 하나하나에 대해
        for (Condition c : conditions) {
            // 실행: 시드 42로 고정한 난수로 300개 상황을 돌려 정확도를 잰다 (매번 같은 결과가 나오게)
            Report r = run(c, new Random(42));
            // 결과를 조건 이름으로 저장한다
            reports.put(c.name(), r);
            // 보고서에 "- 조건 → 결과 요약" 한 줄을 더한다
            out.append("- ").append(c.name()).append(" → ").append(r.summary()).append('\n');
        }
        // target 폴더가 없으면 만든다
        Files.createDirectories(Path.of("target"));
        // 보고서를 파일로 남긴다 (나중에 accuracy.md에 옮겨 적으려고)
        Files.writeString(Path.of("target/view-match-accuracy.txt"), out);
        // 테스트 실행 화면에도 보고서를 출력한다
        System.out.print(out);

        // 확인 준비: 첫 번째 "보통" 조건의 결과를 꺼낸다
        Report base = reports.get(conditions.get(0).name());
        // (도구) assertThat(값).isGreaterThanOrEqualTo(n) = 값이 n 이상이어야 한다는 검사
        // 확인: 신규·기존 판정 정확도가 97% 이상이어야 한다
        assertThat(base.statusAccuracy()).isGreaterThanOrEqualTo(0.97);
        // 확인: 전체 정확도가 95% 이상이어야 한다
        assertThat(base.accuracy()).isGreaterThanOrEqualTo(0.95);
        // (도구) isLessThanOrEqualTo(n) = 값이 n 이하여야 한다는 검사
        // 확인: 원래 있던 흠집을 새것으로 잘못 본 비율(억울한 차감)이 1% 이하여야 한다
        assertThat((double) base.falseNew() / base.total()).isLessThanOrEqualTo(0.01);
    }

    // 테스트 도구: 한 조건으로 300개 상황을 만들어 비교기를 돌리고 정확도를 합산한다.
    //        상황마다 출고 흠집 1~5개(중복 탐지 포함), 반납 때 10%는 못 잡고 30%는 일부만 잡고 10%는 종류를 틀리게 하며, 새 흠집 0~2개를 더한다.
    private static Report run(Condition c, Random rnd) {
        // 비교 규칙 V1로 시점 비교기를 만든다
        ViewComparator comparator = new ViewComparator(MatchRule.V1);
        // 상황별 결과를 모을 빈 목록
        List<Report> reports = new ArrayList<>();
        // 상황 300개를 하나씩 만든다
        for (int s = 0; s < SCENARIOS; s++) {
            // 이 상황의 "출고 때 실제 흠집" 목록
            List<List<Point>> truthOld = new ArrayList<>();
            // 실제 흠집을 1~5개 만든다
            for (int i = 0, n = 1 + rnd.nextInt(5); i < n; i++) {
                // 길이 10~60mm 긁힘 하나를 임의 자리에 놓는다
                truthOld.add(scratch(rnd, 10 + rnd.nextDouble() * 50));
            }
            // 출고 때 탐지된 흠집 목록
            List<DefectObservation> before = new ArrayList<>();
            // 실제 흠집 번호 → 그 흠집을 잡은 탐지 ID들 (정답 맞추기용)
            Map<Integer, List<String>> prevIds = new HashMap<>();
            // 실제 흠집마다
            for (int i = 0; i < truthOld.size(); i++) {
                // 1~2번 탐지한다 (두 카메라가 같은 흠집을 각각 잡는 경우를 흉내)
                for (int k = 0, dup = 1 + rnd.nextInt(2); k < dup; k++) {   // CAM1·CAM2 중복 탐지
                    // 탐지 ID를 "p상황-흠집-번째"로 만든다
                    String id = "p" + s + "-" + i + "-" + k;
                    // 꼭짓점을 조금 흔든 긁힘 관측으로 출고 목록에 넣는다
                    before.add(obs(id, DefectClass.SCRATCH, jitter(truthOld.get(i), rnd)));
                    // 이 탐지 ID를 그 실제 흠집의 정답 목록에 더한다
                    prevIds.computeIfAbsent(i, x -> new ArrayList<>()).add(id);
                }
            }
            // 두 시점 사이 가로 어긋남(±최대 이동)을 정한다
            double dx = (rnd.nextDouble() * 2 - 1) * c.shiftMm();
            // 세로 어긋남도 정한다
            double dy = (rnd.nextDouble() * 2 - 1) * c.shiftMm();
            // 회전 어긋남(±최대 각도)을 라디안으로 정한다
            double th = Math.toRadians((rnd.nextDouble() * 2 - 1) * c.rotateDeg());
            // 반납 때 탐지된 흠집 목록
            List<DefectObservation> now = new ArrayList<>();
            // 반납 탐지 ID → 정답(기존인지 신규인지)
            Map<String, Truth> truths = new HashMap<>();
            // 출고 때 있던 실제 흠집마다
            for (int i = 0; i < truthOld.size(); i++) {
                // 10% 확률로
                if (rnd.nextDouble() < 0.1) {
                    // 반납 때는 못 잡은 것으로 하고 건너뛴다
                    continue;                                       // 이번엔 탐지 못 함
                }
                // 30% 확률로 일부만 잡히게 한다
                List<Point> p = rnd.nextDouble() < 0.3 ? partial(truthOld.get(i), rnd) : truthOld.get(i);
                // 10% 확률로 종류를 깨짐으로 잘못 분류하게 한다
                DefectClass cls = rnd.nextDouble() < 0.1 ? DefectClass.CRACK : DefectClass.SCRATCH;
                // 반납 탐지 ID를 "c상황-흠집"으로 만든다
                String id = "c" + s + "-" + i;
                // 정합 오차와 흔들림을 넣은 관측으로 반납 목록에 넣는다
                now.add(obs(id, cls, jitter(misalign(p, dx, dy, th), rnd)));
                // 정답: 출고 때 그 탐지들과 같은 "기존 흠집"
                truths.put(id, Truth.existing(prevIds.get(i)));
            }
            // 새 흠집을 0~2개 더한다
            for (int j = 0, n = rnd.nextInt(3); j < n; j++) {
                // 기존 흠집과 조건만큼 떨어진 자리에 새 흠집을 놓아 본다
                List<Point> fresh = placeNew(truthOld, c.newGapMm(), rnd);
                // 자리를 찾았으면
                if (fresh != null) {
                    // 새 흠집 ID를 "n상황-번째"로 만든다
                    String id = "n" + s + "-" + j;
                    // 정합 오차와 흔들림을 넣어 반납 목록에 넣는다
                    now.add(obs(id, DefectClass.SCRATCH, jitter(misalign(fresh, dx, dy, th), rnd)));
                    // 정답: "신규 흠집"
                    truths.put(id, Truth.isNew());
                }
            }
            // 비교기로 출고(모든 면)와 반납(모든 면)을 비교하고, 정답과 맞춰 본 결과를 모은다
            reports.add(MatchAccuracy.evaluate(comparator.compare(
                    new ViewSnapshot(before, EnumSet.allOf(Face.class)), new ViewSnapshot(now, EnumSet.allOf(Face.class))),
                    truths));
        }
        // 300개 상황 결과를 합쳐 하나의 보고로 돌려준다
        return MatchAccuracy.sum(MatchRule.V1.version(), reports);
    }

    /** 실제 위치 기준으로 기존 흠집과 newGapMm(보통 조건은 8mm, 가까움 조건은 3~8mm) 떨어진 새 흠집. */
    // 테스트 도구: 기존 흠집과 정해진 거리만큼 떨어진 자리에 새 흠집을 놓는다(못 찾으면 없음).
    private static List<Point> placeNew(List<List<Point>> old, double minGap, Random rnd) {
        // 가까움 조건(최소 거리 8 미만)이면 최대 8mm까지, 아니면 제한 없음
        double maxGap = minGap < 8 ? 8 : Double.MAX_VALUE;
        // 최대 200번 시도한다
        for (int t = 0; t < 200; t++) {
            // 길이 10~40mm 후보 긁힘을 만든다
            List<Point> cand = scratch(rnd, 10 + rnd.nextDouble() * 30);
            // 후보와 가장 가까운 기존 흠집까지의 거리를 잰다
            double g = old.stream().mapToDouble(o -> Shapes.gap(cand, o)).min().orElse(Double.MAX_VALUE);
            // 거리가 조건 범위 안이면
            if (g >= minGap && g < maxGap) {
                // 그 후보를 쓴다
                return cand;
            }
        }
        // 200번 다 실패하면 "없음"을 돌려준다
        return null;
    }

    /** 폭 1 mm, 임의 방향의 긁힘(가는 직사각형). */
    // 테스트 도구: 면(300×210mm) 안 임의 자리·방향에 폭 1mm 긁힘을 만든다.
    private static List<Point> scratch(Random rnd, double length) {
        // 가운데 x를 면 가장자리 30mm 안쪽에서 고른다
        double cx = 30 + rnd.nextDouble() * (FACE_W - 60);
        // 가운데 y도 같은 방식으로 고른다
        double cy = 30 + rnd.nextDouble() * (FACE_H - 60);
        // 방향(0~180°)을 고른다
        double a = rnd.nextDouble() * Math.PI;
        // 길이 방향 반쪽 벡터의 x
        double ux = Math.cos(a) * length / 2;
        // 길이 방향 반쪽 벡터의 y
        double uy = Math.sin(a) * length / 2;
        // 폭 방향 반쪽(0.5mm) 벡터의 x
        double vx = -Math.sin(a) * 0.5;
        // 폭 방향 반쪽 벡터의 y
        double vy = Math.cos(a) * 0.5;
        // 네 꼭짓점으로 이루어진 가는 직사각형을 돌려준다
        return List.of(new Point(cx - ux - vx, cy - uy - vy), new Point(cx + ux - vx, cy + uy - vy),
                new Point(cx + ux + vx, cy + uy + vy), new Point(cx - ux + vx, cy - uy + vy));
    }

    /** 길이의 50~100%만 탐지(앞쪽 두 꼭짓점을 안쪽으로 당김). */
    // 테스트 도구: 긁힘 길이의 50~100%만 잡힌 것처럼 만든다.
    private static List<Point> partial(List<Point> s, Random rnd) {
        // 남길 길이 비율(50~100%)을 고른다
        double keep = 0.5 + rnd.nextDouble() * 0.5;
        // 위쪽 변에서 비율만큼 간 점
        Point a = lerp(s.get(0), s.get(1), keep);
        // 아래쪽 변에서 비율만큼 간 점
        Point b = lerp(s.get(3), s.get(2), keep);
        // 짧아진 직사각형을 돌려준다
        return List.of(s.get(0), a, b, s.get(3));
    }

    /** 면 가운데를 중심으로 회전 th, 이동 (dx, dy): 시점 사이 정합 오차. */
    // 테스트 도구: 두 시점 사이 면 맞추기 오차(면 가운데 기준 회전 + 이동)를 넣는다.
    private static List<Point> misalign(List<Point> s, double dx, double dy, double th) {
        // 회전 중심 x = 면 가운데
        double ox = FACE_W / 2;
        // 회전 중심 y = 면 가운데
        double oy = FACE_H / 2;
        // 꼭짓점마다 가운데 기준으로 th만큼 돌리고 (dx, dy)만큼 옮긴 점을 돌려준다
        return s.stream().map(p -> new Point(
                ox + (p.x() - ox) * Math.cos(th) - (p.y() - oy) * Math.sin(th) + dx,
                oy + (p.x() - ox) * Math.sin(th) + (p.y() - oy) * Math.cos(th) + dy)).toList();
    }

    /** 꼭짓점마다 ±0.5 mm 탐지 흔들림. */
    // 테스트 도구: 꼭짓점마다 ±0.5mm 탐지 흔들림을 넣는다.
    private static List<Point> jitter(List<Point> s, Random rnd) {
        // 꼭짓점마다 x·y를 -0.5~+0.5mm 흔든 점을 돌려준다
        return s.stream().map(p -> new Point(p.x() + rnd.nextDouble() - 0.5, p.y() + rnd.nextDouble() - 0.5)).toList();
    }

    // 테스트 도구: 두 점 사이의 점을 구한다.
    private static Point lerp(Point a, Point b, double t) {
        // a에서 b 쪽으로 비율 t만큼 간 점을 돌려준다
        return new Point(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }

    // 테스트 도구: 윗면(TOP) 흠집 관측 하나를 만든다.
    private static DefectObservation obs(String id, DefectClass cls, List<Point> polygon) {
        // 받은 ID·종류·모양으로 윗면 관측을 만들어 돌려준다
        return new DefectObservation(id, Face.TOP, cls, polygon);
    }
}
