package com.gakdo.trace.capture.inspection.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.comparison.domain.DefectObservation;
import com.gakdo.trace.capture.comparison.domain.MatchRule;
import com.gakdo.trace.capture.comparison.domain.MatchStatus;
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
