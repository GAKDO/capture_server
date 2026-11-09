package com.gakdo.trace.capture.inspection.domain;

import java.util.List;
import java.util.Optional;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.DeviceModel;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.Point;

/**
 * 부스 위 카메라(CAM1·CAM2) 원본 사진 px → 노트북 면 위 mm(11/9). 빠른 검사는 정합이 없어 두 카메라 사진의 좌표계가 다르므로,
 * 같은 흠집인지 보려면 사진 점을 면 위 자리로 옮겨 비교한다.
 *
 * <p>두 카메라 모두 면 <b>전체</b>를 찍는다. 사진 안 면의 네 모서리(사진 비율)는 web {@code src/shared/laptop3d/faces.ts}의 {@code FRAME}과
 * <b>같은 값</b>이다(11/5~11/6 부스 사진 MB02·MB07·MB08·MB13에서 잰 평균, web docs/pin-calibration.md). 부스를 다시 만들거나 카메라를
 * 옮기면 두 곳을 함께 바꾼다. 평균 모서리라 노트북을 놓는 자리 차이만큼 두 카메라 위치가 6~18 mm 어긋난다.
 *
 * <p>옆면·화면은 옆면 카메라 한 대만 찍으므로 옮기지 않는다(빈 값).
 */
public final class BoothFrame {

    /** 부스 C920 해상도. 부스 탐지 polygon은 이 크기 원본 사진 px다(booth camera.py 1920×1080). */
    static final double PHOTO_WIDTH = 1920;
    static final double PHOTO_HEIGHT = 1080;

    /** 기종 치수를 모를 때 쓰는 면 크기(시연 기종 MacBook Air 13 M2, mm). */
    static final double DEFAULT_WIDTH_MM = 304.1;
    static final double DEFAULT_DEPTH_MM = 215.0;

    /** 왼쪽 위·오른쪽 위·오른쪽 아래·왼쪽 아래(사진 비율). web FRAME.CAM1·CAM2와 같은 값. */
    private static final double[][] CAM1 = {{0.159, 0.219}, {0.78, 0.117}, {0.792, 0.912}, {0.215, 0.921}};
    private static final double[][] CAM2 = {{0.233, 0.127}, {0.89, 0.154}, {0.844, 0.905}, {0.255, 0.909}};

    private static final double[][] CAM1_INV = invert(squareToQuad(CAM1));
    private static final double[][] CAM2_INV = invert(squareToQuad(CAM2));

    private BoothFrame() {
    }

    /**
     * 원본 사진 px 한 점 → 면 위 자리(mm, 사진에 보이는 방향 그대로). 윗면·바닥·키보드의 CAM1·CAM2만 옮기고 나머지는 빈 값.
     * 면 밖 점도 끝에 붙이지 않고 그대로 옮긴다(붙이면 서로 다른 바깥 점이 한 모서리로 모여 같은 흠집처럼 보인다).
     * 바닥 사진은 표준 방향과 180° 돌아 있지만 두 카메라가 같은 방향이라 거리 비교에는 상관없다.
     */
    public static Optional<Point> faceMm(Face face, Camera camera, double px, double py, DeviceModel device) {
        if (face != Face.TOP && face != Face.BOT && face != Face.KB) {
            return Optional.empty();
        }
        double[][] inv = switch (camera) {
            case CAM1 -> CAM1_INV;
            case CAM2 -> CAM2_INV;
            default -> null;
        };
        if (inv == null || !Double.isFinite(px) || !Double.isFinite(py)) {
            return Optional.empty();
        }
        double x = px / PHOTO_WIDTH;
        double y = py / PHOTO_HEIGHT;
        double w = inv[2][0] * x + inv[2][1] * y + inv[2][2];
        if (Math.abs(w) < 1e-12) {
            return Optional.empty();
        }
        double s = (inv[0][0] * x + inv[0][1] * y + inv[0][2]) / w;
        double t = (inv[1][0] * x + inv[1][1] * y + inv[1][2]) / w;
        double width = DEFAULT_WIDTH_MM;
        double depth = DEFAULT_DEPTH_MM;
        if (device != null && device.dimensions() != null) {
            width = device.dimensions().widthMm();
            depth = device.dimensions().depthMm();
        }
        return Optional.of(new Point(s * width, t * depth));
    }

    /** polygon 꼭짓점 평균(원본 px) → 면 위 자리(mm). */
    public static Optional<Point> centerMm(Face face, Camera camera, List<List<Double>> pxPolygon, DeviceModel device) {
        if (pxPolygon == null || pxPolygon.isEmpty()) {
            return Optional.empty();
        }
        double sx = 0;
        double sy = 0;
        for (List<Double> p : pxPolygon) {
            if (p == null || p.size() != 2 || p.get(0) == null || p.get(1) == null) {
                return Optional.empty();
            }
            sx += p.get(0);
            sy += p.get(1);
        }
        return faceMm(face, camera, sx / pxPolygon.size(), sy / pxPolygon.size(), device);
    }

    /** 단위 사각형 (0,0)(1,0)(1,1)(0,1) → 네 모서리 호모그래피(Heckbert 닫힌 꼴, web squareToQuad와 같음). */
    private static double[][] squareToQuad(double[][] q) {
        double x0 = q[0][0];
        double y0 = q[0][1];
        double x1 = q[1][0];
        double y1 = q[1][1];
        double x2 = q[2][0];
        double y2 = q[2][1];
        double x3 = q[3][0];
        double y3 = q[3][1];
        double dx1 = x1 - x2;
        double dx2 = x3 - x2;
        double dy1 = y1 - y2;
        double dy2 = y3 - y2;
        double sx = x0 - x1 + x2 - x3;
        double sy = y0 - y1 + y2 - y3;
        double den = dx1 * dy2 - dx2 * dy1;
        double g = (sx * dy2 - dx2 * sy) / den;
        double h = (dx1 * sy - sx * dy1) / den;
        return new double[][] {
            {x1 - x0 + g * x1, x3 - x0 + h * x3, x0},
            {y1 - y0 + g * y1, y3 - y0 + h * y3, y0},
            {g, h, 1},
        };
    }

    private static double[][] invert(double[][] m) {
        double a = m[0][0];
        double b = m[0][1];
        double c = m[0][2];
        double d = m[1][0];
        double e = m[1][1];
        double f = m[1][2];
        double g = m[2][0];
        double h = m[2][1];
        double i = m[2][2];
        double ca = e * i - f * h;
        double cb = f * g - d * i;
        double cc = d * h - e * g;
        double det = a * ca + b * cb + c * cc;
        return new double[][] {
            {ca / det, (c * h - b * i) / det, (b * f - c * e) / det},
            {cb / det, (a * i - c * g) / det, (c * d - a * f) / det},
            {cc / det, (b * g - a * h) / det, (a * e - b * d) / det},
        };
    }
}
