package com.gakdo.trace.capture.comparison.domain;

import java.util.List;

/** polygon 거리 계산(mm). 꼭짓점은 순서대로 이어지고 마지막 점은 첫 점과 이어진다. */
public final class Shapes {

    private Shapes() {
    }

    /** 꼭짓점 평균. 거리가 같은 후보끼리 순서를 정할 때만 쓴다. */
    public static Point centroid(List<Point> polygon) {
        double x = 0;
        double y = 0;
        for (Point p : polygon) {
            x += p.x();
            y += p.y();
        }
        return new Point(x / polygon.size(), y / polygon.size());
    }

    /**
     * 두 polygon 사이 가장 가까운 거리. 겹치거나 한쪽이 다른 쪽 안에 있으면 0.
     * 긴 긁힘의 일부만 다시 탐지돼도 거리가 0이 되도록 중심점 거리 대신 이 값을 쓴다.
     */
    public static double gap(List<Point> a, List<Point> b) {
        if (inside(a.get(0), b) || inside(b.get(0), a)) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < a.size(); i++) {
            Point a1 = a.get(i);
            Point a2 = a.get((i + 1) % a.size());
            for (int j = 0; j < b.size(); j++) {
                Point b1 = b.get(j);
                Point b2 = b.get((j + 1) % b.size());
                if (crosses(a1, a2, b1, b2)) {
                    return 0;
                }
                best = Math.min(best, Math.min(
                        Math.min(toSegment(a1, b1, b2), toSegment(a2, b1, b2)),
                        Math.min(toSegment(b1, a1, a2), toSegment(b2, a1, a2))));
            }
        }
        return best;
    }

    public static double distance(Point p, Point q) {
        return Math.hypot(p.x() - q.x(), p.y() - q.y());
    }

    static boolean inside(Point p, List<Point> polygon) {
        boolean in = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point pi = polygon.get(i);
            Point pj = polygon.get(j);
            if ((pi.y() > p.y()) != (pj.y() > p.y())
                    && p.x() < (pj.x() - pi.x()) * (p.y() - pi.y()) / (pj.y() - pi.y()) + pi.x()) {
                in = !in;
            }
        }
        return in;
    }

    private static double toSegment(Point p, Point s1, Point s2) {
        double dx = s2.x() - s1.x();
        double dy = s2.y() - s1.y();
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((p.x() - s1.x()) * dx + (p.y() - s1.y()) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        return distance(p, new Point(s1.x() + t * dx, s1.y() + t * dy));
    }

    private static boolean crosses(Point p1, Point p2, Point q1, Point q2) {
        double d1 = cross(q1, q2, p1);
        double d2 = cross(q1, q2, p2);
        double d3 = cross(p1, p2, q1);
        double d4 = cross(p1, p2, q2);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0)) && d1 != 0 && d2 != 0 && d3 != 0 && d4 != 0;
    }

    private static double cross(Point o, Point a, Point b) {
        return (a.x() - o.x()) * (b.y() - o.y()) - (a.y() - o.y()) * (b.x() - o.x());
    }
}
