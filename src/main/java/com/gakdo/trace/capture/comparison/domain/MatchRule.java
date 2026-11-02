package com.gakdo.trace.capture.comparison.domain;

/**
 * 버전이 있는 짝짓기 규칙. 값을 바꾸면 version도 올린다(이미 저장된 판정은 그때의 버전으로 남는다).
 *
 * @param version            저장·리포트에 남기는 규칙 이름(32자 이하)
 * @param maxGapMm           이 거리(두 polygon 사이 가장 가까운 거리) 안이면 같은 흠집으로 본다
 * @param classChangePenaltyMm 종류가 다른 후보에 더하는 거리. 같은 종류 후보를 먼저 고른다
 */
public record MatchRule(String version, double maxGapMm, double classChangePenaltyMm) {

    /**
     * v1: 정합 오차(이동 몇 mm + 회전 1° 안쪽)를 덮도록 5 mm. 종류가 바뀌어도(scratch↔crack 오분류) 짝지을 수 있게 하되 오염은 오염끼리만.
     * 억울한 "신규" 판정(고객 책임)이 놓친 신규보다 나쁘다고 보고 너그럽게 잡았다.
     */
    public static final MatchRule V1 = new MatchRule("view-match-v1", 5.0, 2.0);

    public MatchRule {
        if (version == null || version.isBlank() || version.length() > 32) {
            throw new IllegalArgumentException("version은 1~32자");
        }
        if (!Double.isFinite(maxGapMm) || !Double.isFinite(classChangePenaltyMm)
                || maxGapMm < 0 || classChangePenaltyMm < 0) {
            throw new IllegalArgumentException("거리는 유한한 0 이상");
        }
    }

    /** 두 종류를 짝지을 수 있나. 오염(stain)은 오염끼리만, 나머지(긁힘·찍힘·균열)는 서로 오분류될 수 있어 허용한다. */
    public boolean compatible(DefectClass a, DefectClass b) {
        return (a == DefectClass.STAIN) == (b == DefectClass.STAIN);
    }
}
