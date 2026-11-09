package com.gakdo.trace.capture.inspection.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.comparison.domain.DefectObservation;
import com.gakdo.trace.capture.comparison.domain.MatchRule;
import com.gakdo.trace.capture.comparison.domain.MatchStatus;
import com.gakdo.trace.capture.comparison.domain.Point;
import com.gakdo.trace.capture.comparison.domain.Shapes;

/**
 * 같은 흠집 묶기. 부스는 한 면을 조명 4개 × 카메라 2대로 찍어 같은 흠집이 사진 여러 장에서 탐지된다.
 * 같은 면에서 비교 규칙 거리(view-match-v1: 5 mm) 안이고 종류가 맞으면(오염은 오염끼리) 하나의 흠집으로 센다.
 */
public final class DefectGroups {

    private DefectGroups() {
    }

    /**
     * 묶기 재료.
     *
     * @param lengthMm 잴 수 없으면 null
     * @param status   비교 판정. 비교하지 않았으면(출고·이전 시점 판정 불가 면) null
     */
    public record Item(DefectObservation observation, Double lengthMm, MatchStatus status) {
    }

    /**
     * 묶인 흠집 하나.
     *
     * @param defectClass 묶음 안에서 가장 심한 종류(균열 > 찍힘 > 긁힘 > 오염)
     * @param lengthMm    묶음 안 가장 긴 길이(모두 모르면 null)
     * @param isNew       묶음의 판정이 모두 NEW면 true, 하나라도 EXISTING이면 false, 비교하지 않았으면 null
     */
    public record Group(Face face, DefectClass defectClass, Double lengthMm, Boolean isNew, List<String> defectIds) {
    }

    public static List<Group> group(List<Item> items, MatchRule rule) {
        int n = items.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                DefectObservation a = items.get(i).observation();
                DefectObservation b = items.get(j).observation();
                if (a.face() == b.face() && rule.compatible(a.defectClass(), b.defectClass())
                        && Shapes.gap(a.polygon(), b.polygon()) <= rule.maxGapMm()) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, List<Item>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(items.get(i));
        }
        return byRoot.values().stream().map(DefectGroups::merge).toList();
    }

    /**
     * 카메라 사이 묶기의 재료: 한 카메라(또는 이미 합친 여러 카메라) 사진에서 묶인 흠집과 그 면 위 자리.
     *
     * @param cameras 묶음의 흠집을 찍은 카메라
     * @param at      면 위 자리(mm). 모르면 null(합치지 않음)
     */
    public record Placed(Group group, Set<Camera> cameras, Point at) {
    }

    /**
     * 카메라 사이 묶기(11/9). 부스 위 카메라 2대가 면 전체를 각자 찍으므로 같은 흠집이 CAM1·CAM2 사진에 한 번씩 잡힌다.
     * 같은 면·짝지을 수 있는 종류이고 면 위 자리가 maxMm 안이면 하나로 합친다. 가까운 쌍부터 합치며, 찍은 카메라가 겹치는 묶음끼리는
     * 합치지 않는다(같은 카메라에 따로 잡힌 두 흠집은 서로 다른 흠집이다). 그래서 CAM1 묶음 하나는 CAM2 묶음 하나와만 합쳐진다.
     *
     * @return 합친 묶음(입력 순서 유지, 합쳐진 묶음은 먼저 나온 쪽 자리에)
     */
    public static List<Group> mergeAcrossCameras(List<Placed> placed, double maxMm, MatchRule rule) {
        int n = placed.size();
        record Pair(int i, int j, double d) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Placed a = placed.get(i);
                Placed b = placed.get(j);
                if (a.at() == null || b.at() == null || a.group().face() != b.group().face()
                        || !rule.compatible(a.group().defectClass(), b.group().defectClass())) {
                    continue;
                }
                double d = Shapes.distance(a.at(), b.at());
                if (d <= maxMm) {
                    pairs.add(new Pair(i, j, d));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::d));
        int[] parent = new int[n];
        List<Set<Camera>> cams = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            cams.add(java.util.EnumSet.copyOf(placed.get(i).cameras().isEmpty()
                    ? Set.of(Camera.PHONE) : placed.get(i).cameras()));
        }
        for (Pair p : pairs) {
            int ri = find(parent, p.i());
            int rj = find(parent, p.j());
            if (ri == rj || cams.get(ri).stream().anyMatch(cams.get(rj)::contains)) {
                continue;
            }
            int root = Math.min(ri, rj);
            int other = Math.max(ri, rj);
            parent[other] = root;
            cams.get(root).addAll(cams.get(other));
        }
        Map<Integer, List<Group>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(placed.get(i).group());
        }
        return byRoot.values().stream().map(DefectGroups::combine).toList();
    }

    /** 이미 묶인 흠집 여러 개를 하나로(merge와 같은 규칙: 가장 심한 종류, 가장 긴 길이, EXISTING이 하나라도 있으면 신규 아님). */
    private static Group combine(List<Group> members) {
        if (members.size() == 1) {
            return members.get(0);
        }
        DefectClass worst = members.stream().map(Group::defectClass)
                .max(Comparator.comparingInt(DefectGroups::severity)).orElseThrow();
        Double length = members.stream().map(Group::lengthMm).filter(v -> v != null)
                .max(Double::compare).orElse(null);
        Boolean isNew = null;
        for (Group g : members) {
            if (Boolean.FALSE.equals(g.isNew())) {
                isNew = false;
                break;
            }
            if (Boolean.TRUE.equals(g.isNew())) {
                isNew = true;
            }
        }
        List<String> ids = members.stream().flatMap(g -> g.defectIds().stream()).toList();
        return new Group(members.get(0).face(), worst, length, isNew, ids);
    }

    private static Group merge(List<Item> members) {
        Face face = members.get(0).observation().face();
        DefectClass worst = members.stream().map(i -> i.observation().defectClass())
                .max(Comparator.comparingInt(DefectGroups::severity)).orElseThrow();
        Double length = members.stream().map(Item::lengthMm).filter(v -> v != null)
                .max(Double::compare).orElse(null);
        Boolean isNew = null;
        for (Item i : members) {
            if (i.status() == MatchStatus.EXISTING) {
                isNew = false;
                break;
            }
            if (i.status() == MatchStatus.NEW) {
                isNew = true;
            }
        }
        List<String> ids = members.stream().map(i -> i.observation().defectId()).toList();
        return new Group(face, worst, length, isNew, ids);
    }

    static int severity(DefectClass c) {
        return switch (c) {
            case STAIN -> 0;
            case SCRATCH -> 1;
            case DENT -> 2;
            case CRACK -> 3;
        };
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
}
