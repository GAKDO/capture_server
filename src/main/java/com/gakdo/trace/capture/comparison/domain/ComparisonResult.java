package com.gakdo.trace.capture.comparison.domain;

import java.util.List;

/**
 * 시점 비교 결과.
 *
 * @param ruleVersion      쓴 규칙 버전(MatchRule.version). 결과와 함께 저장한다
 * @param matches          비교한 흠집마다 판정 하나(입력 순서)
 * @param unjudgedDefectIds 판정 불가 면에 있어 비교하지 않은 흠집
 */
public record ComparisonResult(String ruleVersion, List<DefectMatch> matches, List<String> unjudgedDefectIds) {

    public ComparisonResult {
        matches = List.copyOf(matches);
        unjudgedDefectIds = List.copyOf(unjudgedDefectIds);
    }
}
