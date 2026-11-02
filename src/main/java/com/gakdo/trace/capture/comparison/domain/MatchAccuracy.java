package com.gakdo.trace.capture.comparison.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 테스트 흠집(정답을 아는 흠집)으로 판정 정확도를 잰다(FR-INS-03 수용 기준 "테스트 흠집의 판정 정확도를 기록한다").
 * 실제 기기에서 정답을 단 흠집이 생기면 같은 함수에 넣어 잰다.
 */
public final class MatchAccuracy {

    private MatchAccuracy() {
    }

    /**
     * 정답 하나. EXISTING이면 맞는 이전 흠집 ID들(같은 흠집이 이전 시점 사진 여러 장에 탐지됐으면 그중 아무거나 맞다).
     */
    public record Truth(MatchStatus status, Set<String> acceptablePrevIds) {

        public static Truth isNew() {
            return new Truth(MatchStatus.NEW, Set.of());
        }

        public static Truth existing(Collection<String> prevIds) {
            return new Truth(MatchStatus.EXISTING, Set.copyOf(prevIds));
        }
    }

    /**
     * @param falseNew      정답은 기존인데 신규로 판정(고객에게 억울한 판정)
     * @param falseExisting 정답은 신규인데 기존으로 판정(놓친 신규 흠집)
     * @param wrongPrev     기존은 맞지만 다른 흠집과 짝지음
     * @param missing       정답에는 있는데 결과에 없음(판정 불가 면 등)
     */
    public record Report(String ruleVersion, int total, int correct, int falseNew, int falseExisting,
            int wrongPrev, int missing) {

        /** 짝까지 맞은 비율. */
        public double accuracy() {
            return total == 0 ? 1.0 : (double) correct / total;
        }

        /** 신규·기존 판정만 맞은 비율(짝이 다른 기존 흠집이어도 맞음으로 본다). 등급에 쓰이는 값이다. */
        public double statusAccuracy() {
            return total == 0 ? 1.0 : (double) (correct + wrongPrev) / total;
        }

        public String summary() {
            return String.format(java.util.Locale.ROOT,
                    "%s: 신규·기존 판정 %.1f%%, 짝까지 맞음 %.1f%% (%d/%d), 억울한 신규 %d, 놓친 신규 %d, 짝 틀림 %d, 빠짐 %d",
                    ruleVersion, statusAccuracy() * 100, accuracy() * 100, correct, total,
                    falseNew, falseExisting, wrongPrev, missing);
        }
    }

    public static Report evaluate(ComparisonResult result, Map<String, Truth> truths) {
        Map<String, DefectMatch> byId = new HashMap<>();
        result.matches().forEach(m -> byId.put(m.defectId(), m));
        int correct = 0;
        int falseNew = 0;
        int falseExisting = 0;
        int wrongPrev = 0;
        int missing = 0;
        for (Map.Entry<String, Truth> e : truths.entrySet()) {
            DefectMatch m = byId.get(e.getKey());
            Truth t = e.getValue();
            if (m == null) {
                missing++;
            } else if (t.status() == MatchStatus.NEW) {
                if (m.status() == MatchStatus.NEW) {
                    correct++;
                } else {
                    falseExisting++;
                }
            } else if (m.status() == MatchStatus.NEW) {
                falseNew++;
            } else if (t.acceptablePrevIds().contains(m.prevDefectId())) {
                correct++;
            } else {
                wrongPrev++;
            }
        }
        return new Report(result.ruleVersion(), truths.size(), correct, falseNew, falseExisting, wrongPrev, missing);
    }

    /** 여러 보고서를 더한다(시나리오 여러 개의 합계). */
    public static Report sum(String ruleVersion, Collection<Report> reports) {
        int[] s = new int[6];
        for (Report r : reports) {
            s[0] += r.total();
            s[1] += r.correct();
            s[2] += r.falseNew();
            s[3] += r.falseExisting();
            s[4] += r.wrongPrev();
            s[5] += r.missing();
        }
        return new Report(ruleVersion, s[0], s[1], s[2], s[3], s[4], s[5]);
    }
}
