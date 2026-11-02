package com.gakdo.trace.capture.inspection.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 사진 px → 표준 좌표 px 3×3 호모그래피(행 우선, contracts trace.inspect.completed alignments[].homography).
 * 행렬이 3×3이 아니거나 유한하지 않거나 역행렬이 없으면 만들 수 없다(정합 결과로 쓸 수 없는 값).
 */
public final class Homography {

    /** 이보다 작은 행렬식·동차 좌표 w는 0으로 본다(점이 무한대로 간다). */
    private static final double EPSILON = 1e-12;

    private final double[][] m;

    private Homography(double[][] m) {
        this.m = m;
    }

    public static Homography of(List<List<Double>> rows) {
        if (rows == null || rows.size() != 3) {
            throw new IllegalArgumentException("호모그래피는 3×3 행렬이어야 합니다");
        }
        double[][] m = new double[3][3];
        for (int r = 0; r < 3; r++) {
            List<Double> row = rows.get(r);
            if (row == null || row.size() != 3) {
                throw new IllegalArgumentException("호모그래피는 3×3 행렬이어야 합니다");
            }
            for (int c = 0; c < 3; c++) {
                Double v = row.get(c);
                if (v == null || !Double.isFinite(v)) {
                    throw new IllegalArgumentException("호모그래피 값은 유한한 수여야 합니다");
                }
                m[r][c] = v;
            }
        }
        Homography h = new Homography(m);
        if (Math.abs(h.determinant()) < EPSILON) {
            throw new IllegalArgumentException("호모그래피의 역행렬이 없습니다");
        }
        return h;
    }

    private double determinant() {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /** 표준 좌표 → 사진 px(폰 탐지의 원본 px를 구할 때). */
    public Homography inverse() {
        double det = determinant();
        double[][] inv = new double[3][3];
        inv[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
        inv[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
        inv[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
        inv[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
        inv[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
        inv[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
        inv[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
        inv[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
        inv[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
        return new Homography(inv);
    }

    /**
     * polygon [[x, y], …]의 꼭짓점마다 옮긴다. 어떤 점이 무한대로 가면(w ≈ 0) 옮길 수 없다.
     * 좌표는 소수 둘째 자리(0.01 px)까지 남긴다.
     */
    public List<List<Double>> map(List<List<Double>> polygon) {
        List<List<Double>> out = new ArrayList<>(polygon.size());
        for (List<Double> p : polygon) {
            double x = p.get(0);
            double y = p.get(1);
            double w = m[2][0] * x + m[2][1] * y + m[2][2];
            if (Math.abs(w) < EPSILON) {
                throw new IllegalArgumentException("점이 표준 좌표 밖(무한대)으로 갑니다");
            }
            double nx = (m[0][0] * x + m[0][1] * y + m[0][2]) / w;
            double ny = (m[1][0] * x + m[1][1] * y + m[1][2]) / w;
            out.add(List.of(round(nx), round(ny)));
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
