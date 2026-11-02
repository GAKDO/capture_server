package com.gakdo.trace.capture.comparison.domain;

/**
 * 지금 시점 흠집 하나의 판정. inspection.defect_match 한 행에 그대로 들어간다.
 *
 * @param prevDefectId EXISTING이면 짝지은 이전 흠집 publicId, NEW면 null
 * @param distanceMm   EXISTING이면 짝과의 거리, NEW면 같은 면에서 가장 가까운 이전 흠집과의 거리(없으면 null)
 */
public record DefectMatch(String defectId, MatchStatus status, String prevDefectId, Double distanceMm) {
}
