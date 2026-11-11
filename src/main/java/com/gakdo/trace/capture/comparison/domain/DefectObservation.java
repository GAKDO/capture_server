package com.gakdo.trace.capture.comparison.domain;

import java.util.List;

import com.gakdo.trace.capture.captureset.domain.Face;

/**
 * 비교에 쓰는 흠집 하나. polygon은 표준 좌표 mm다. DB(inspection.defect.polygon)는 표준 좌표 px로 저장하므로
 * {@link #fromStandardPx}로 만든다(1 px = 0.25 mm, contracts capture/faces.md).
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

    /** 표준 좌표 px 1개가 몇 mm인지(contracts capture/faces.md: 1 px = 0.25 mm). */
    public static final double MM_PER_PX = 0.25;

    /** DB에 저장된 표준 좌표 px polygon [[x, y], …]으로 만든다. 거리 계산은 mm로 바꿔서 한다. */
    public static DefectObservation fromStandardPx(String defectId, Face face, DefectClass defectClass,
            List<List<Double>> pxPolygon) {
        if (pxPolygon == null) {
            throw new IllegalArgumentException("polygon은 꼭짓점 3개 이상: " + defectId);
        }
        List<Point> mm = pxPolygon.stream().map(xy -> {
            if (xy == null || xy.size() != 2 || xy.get(0) == null || xy.get(1) == null) {
                throw new IllegalArgumentException("꼭짓점은 [x, y]: " + defectId);
            }
            return new Point(xy.get(0) * MM_PER_PX, xy.get(1) * MM_PER_PX);
        }).toList();
        return new DefectObservation(defectId, face, defectClass, mm);
    }
}
