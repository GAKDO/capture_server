package com.gakdo.trace.capture.comparison.domain;

/** 표준 좌표의 한 점(mm). 원점은 펼친 면의 왼쪽 위, x는 오른쪽, y는 아래(contracts capture/faces.md). */
public record Point(double x, double y) {

    public Point {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("좌표는 유한한 값이어야 한다");
        }
    }
}
