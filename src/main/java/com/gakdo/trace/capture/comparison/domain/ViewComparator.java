package com.gakdo.trace.capture.comparison.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import com.gakdo.trace.capture.captureset.domain.Face;

/**
 * 시점 비교(FR-INS-03). 지금 시점 흠집마다 같은 면의 이전 시점 흠집 중 표준 좌표 거리가 가장 가까운 것을 찾아,
 * 규칙 거리 안이면 EXISTING(짝 ID와 거리), 아니면 NEW로 판정한다.
 *
 * <ul>
 *   <li>여러 지금 흠집이 같은 이전 흠집과 짝지어질 수 있다. 같은 흠집이 CAM1·CAM2·조명별 사진에서 여러 번 탐지되거나,
 *       긴 긁힘이 둘로 나뉘어 탐지되기 때문이다(defect_match.prev_defect_id는 고유가 아니다).</li>
 *   <li>면이 지금이나 이전 시점에서 판정 불가면 그 면의 흠집은 비교하지 않고 unjudgedDefectIds로 돌려준다.</li>
 *   <li>이전 시점이 없으면(첫 시점) 판정할 수 있는 면의 흠집은 모두 NEW다.</li>
 * </ul>
 * 상태가 없고 같은 입력에는 같은 결과를 낸다.
 */
public final class ViewComparator {

    private final MatchRule rule;

    public ViewComparator(MatchRule rule) {
        this.rule = rule;
    }

    public ComparisonResult compare(ViewSnapshot previous, ViewSnapshot current) {
        List<DefectMatch> matches = new ArrayList<>();
        List<String> unjudged = new ArrayList<>();
        for (DefectObservation defect : current.defects()) {
            if (!comparable(defect.face(), previous, current)) {
                unjudged.add(defect.defectId());
            } else if (previous == null) {
                matches.add(new DefectMatch(defect.defectId(), MatchStatus.NEW, null, null));
            } else {
                matches.add(judge(defect, previous.defects()));
            }
        }
        return new ComparisonResult(rule.version(), matches, unjudged);
    }

    private static boolean comparable(Face face, ViewSnapshot previous, ViewSnapshot current) {
        Set<Face> now = current.judgeableFaces();
        return now.contains(face) && (previous == null || previous.judgeableFaces().contains(face));
    }

    private DefectMatch judge(DefectObservation defect, List<DefectObservation> before) {
        Point center = Shapes.centroid(defect.polygon());
        List<Candidate> sameFace = before.stream()
                .filter(prev -> prev.face() == defect.face())
                .map(prev -> new Candidate(prev, Shapes.gap(defect.polygon(), prev.polygon()),
                        Shapes.distance(center, Shapes.centroid(prev.polygon()))))
                .toList();
        Optional<Candidate> best = sameFace.stream()
                .filter(c -> rule.compatible(defect.defectClass(), c.prev().defectClass()))
                .filter(c -> c.gap() <= rule.maxGapMm())
                .min(Comparator.comparingDouble((Candidate c) -> cost(defect, c))
                        .thenComparingDouble(Candidate::centerDistance)
                        .thenComparing(c -> c.prev().defectId()));
        if (best.isPresent()) {
            Candidate c = best.get();
            return new DefectMatch(defect.defectId(), MatchStatus.EXISTING, c.prev().defectId(), round(c.gap()));
        }
        OptionalDouble nearest = sameFace.stream().mapToDouble(Candidate::gap).min();
        return new DefectMatch(defect.defectId(), MatchStatus.NEW, null,
                nearest.isPresent() ? round(nearest.getAsDouble()) : null);
    }

    private double cost(DefectObservation defect, Candidate c) {
        return c.gap() + (defect.defectClass() == c.prev().defectClass() ? 0 : rule.classChangePenaltyMm());
    }

    private static double round(double mm) {
        return Math.round(mm * 100) / 100.0;
    }

    private record Candidate(DefectObservation prev, double gap, double centerDistance) {
    }
}
