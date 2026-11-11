package com.gakdo.trace.capture.inspection.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.DefectGroups.Group;

/**
 * 외관 등급 규칙(FR-INS-04). 값을 바꾸면 VERSION을 올린다(이미 매긴 등급은 그때의 버전으로 남는다).
 *
 * <p>grade-rules-v1 (묶인 흠집 기준, 판정 불가 면과 오염은 뺌):
 * <ul>
 *   <li>D: 균열 1개 이상 또는 찍힘 3개 이상</li>
 *   <li>C: 찍힘 1~2개, 또는 긁힘 5개 이상, 또는 30 mm 이상 긁힘</li>
 *   <li>B: 긁힘 1~4개(모두 30 mm 미만)</li>
 *   <li>A: 흠집 없음</li>
 * </ul>
 * 등급은 지금 기기 외관 전체로 매기고, 신규 흠집은 근거 문장에 따로 적는다(출고에도 등급이 있다).
 */
public final class GradeRules {

    public static final String VERSION = "grade-rules-v1";
    static final double LONG_SCRATCH_MM = 30.0;
    static final int MANY_SCRATCHES = 5;
    static final int MANY_DENTS = 3;

    private GradeRules() {
    }

    public record Outcome(Grade grade, String ruleVersion, List<String> reasons) {
    }

    /**
     * @param groups      판정 가능 면의 묶인 흠집(오염 포함, 여기서 뺀다)
     * @param unjudgeable 판정 불가 면과 사유
     * @param baseline    비교할 앞 시점이 없음(출고)
     */
    public static Outcome evaluate(List<Group> groups, Map<Face, String> unjudgeable, boolean baseline) {
        Map<DefectClass, List<Group>> byClass = new EnumMap<>(DefectClass.class);
        for (Group g : groups) {
            byClass.computeIfAbsent(g.defectClass(), c -> new ArrayList<>()).add(g);
        }
        Verdict verdict = grade(byClass);

        List<String> reasons = new ArrayList<>();
        reasons.add(verdict.why() + " → " + verdict.grade());
        if (baseline) {
            reasons.add("출고 기준선(비교할 앞 시점 없음)");
        } else {
            addNewDefectReasons(byClass, reasons);
        }
        int stains = count(byClass, DefectClass.STAIN);
        if (stains > 0) {
            reasons.add("오염 " + stains + "개는 등급에서 뺌");
        }
        unjudgeable.forEach((face, reason) -> reasons.add("판정 불가 면 " + face + ": " + reason));
        return new Outcome(verdict.grade(), VERSION, List.copyOf(reasons));
    }

    /** 등급과 그 근거 문장 하나. */
    private record Verdict(Grade grade, String why) {
    }

    private static Verdict grade(Map<DefectClass, List<Group>> byClass) {
        int cracks = count(byClass, DefectClass.CRACK);
        int dents = count(byClass, DefectClass.DENT);
        List<Group> scratches = byClass.getOrDefault(DefectClass.SCRATCH, List.of());
        double longest = longest(scratches);

        if (cracks > 0) {
            return new Verdict(Grade.D, "균열 " + cracks + "개");
        }
        if (dents >= MANY_DENTS) {
            return new Verdict(Grade.D, "찍힘 " + dents + "개");
        }
        if (dents > 0) {
            return new Verdict(Grade.C, "찍힘 " + dents + "개");
        }
        if (longest >= LONG_SCRATCH_MM) {
            return new Verdict(Grade.C, "긴 긁힘(" + mm(longest) + ")");
        }
        if (scratches.size() >= MANY_SCRATCHES) {
            return new Verdict(Grade.C, "긁힘 " + scratches.size() + "개");
        }
        if (!scratches.isEmpty()) {
            return new Verdict(Grade.B, "긁힘 " + scratches.size() + "개(최대 " + mm(longest) + ")");
        }
        return new Verdict(Grade.A, "흠집 없음");
    }

    private static double longest(List<Group> scratches) {
        return scratches.stream().map(Group::lengthMm).filter(Objects::nonNull)
                .max(Double::compare).orElse(0.0);
    }

    /** 앞 시점과 비교했을 때 종류별 신규 흠집 개수(와 가장 긴 길이)를 근거에 더한다. */
    private static void addNewDefectReasons(Map<DefectClass, List<Group>> byClass, List<String> reasons) {
        for (DefectClass c : List.of(DefectClass.CRACK, DefectClass.DENT, DefectClass.SCRATCH)) {
            List<Group> fresh = byClass.getOrDefault(c, List.of()).stream()
                    .filter(g -> Boolean.TRUE.equals(g.isNew())).toList();
            if (!fresh.isEmpty()) {
                Double max = fresh.stream().map(Group::lengthMm).filter(Objects::nonNull)
                        .max(Double::compare).orElse(null);
                reasons.add("신규 " + name(c) + " " + fresh.size() + "개" + (max == null ? "" : "(" + mm(max) + ")"));
            }
        }
    }

    private static int count(Map<DefectClass, List<Group>> byClass, DefectClass c) {
        return byClass.getOrDefault(c, List.of()).size();
    }

    private static String name(DefectClass c) {
        return switch (c) {
            case SCRATCH -> "긁힘";
            case DENT -> "찍힘";
            case CRACK -> "균열";
            case STAIN -> "오염";
        };
    }

    private static String mm(double v) {
        return String.format(Locale.ROOT, "%.0fmm", v);
    }
}
