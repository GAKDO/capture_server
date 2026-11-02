package com.gakdo.trace.capture.inspection.domain;

import java.util.List;

import com.gakdo.trace.capture.comparison.domain.DefectObservation;

/**
 * 표준 좌표 px polygon의 길이(mm)·넓이(mm²). 1 px = 0.25 mm(contracts capture/faces.md).
 * 부스 탐지 계약에는 길이 칸이 없어 표준 좌표로 옮긴 뒤 여기서 잰다.
 */
public final class PolygonMeasure {

    private static final double MM = DefectObservation.MM_PER_PX;

    private PolygonMeasure() {
    }

    /** 꼭짓점 사이 가장 먼 거리. 가늘고 긴 긁힘이면 그 길이가 된다. */
    public static double lengthMm(List<List<Double>> px) {
        double best = 0;
        for (int i = 0; i < px.size(); i++) {
            for (int j = i + 1; j < px.size(); j++) {
                best = Math.max(best, Math.hypot(px.get(i).get(0) - px.get(j).get(0),
                        px.get(i).get(1) - px.get(j).get(1)));
            }
        }
        return round(best * MM);
    }

    /** 신발끈 공식 넓이. 꼭짓점 순서 방향과 상관없이 양수. */
    public static double areaMm2(List<List<Double>> px) {
        double sum = 0;
        for (int i = 0; i < px.size(); i++) {
            List<Double> a = px.get(i);
            List<Double> b = px.get((i + 1) % px.size());
            sum += a.get(0) * b.get(1) - b.get(0) * a.get(1);
        }
        return round(Math.abs(sum) / 2 * MM * MM);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
