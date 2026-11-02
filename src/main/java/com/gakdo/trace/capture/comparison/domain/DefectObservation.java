package com.gakdo.trace.capture.comparison.domain;

import java.util.List;

import com.gakdo.trace.capture.captureset.domain.Face;

/**
 * 비교에 쓰는 흠집 하나. 정합 뒤 표준 좌표 polygon(mm)이 있어야 한다(inspection.defect.polygon).
 *
 * @param defectId 흠집 publicId
 */
public record DefectObservation(String defectId, Face face, DefectClass defectClass, List<Point> polygon) {

    public DefectObservation {
        if (defectId == null || face == null || defectClass == null) {
            throw new IllegalArgumentException("defectId·face·defectClass는 필수");
        }
        if (polygon == null || polygon.size() < 3) {
            throw new IllegalArgumentException("polygon은 꼭짓점 3개 이상: " + defectId);
        }
        polygon = List.copyOf(polygon);
    }
}
