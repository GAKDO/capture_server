package com.gakdo.trace.capture.inspection.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;

/**
 * 반납 정산 규칙표(11/10, contracts getCheckpointSettlement). 금액을 바꾸면 VERSION을 올린다.
 * 저장하지 않고 읽을 때 계산하므로 같은 검사 결과·같은 버전이면 누구나 같은 금액이 나온다.
 *
 * <p>settlement-rules-v2 (기기 기준가 대비 비율, 천 원 단위 반올림, 반납 시점의 신규 흠집 묶음 하나마다):
 * <ul>
 *   <li>긁힘: 10 mm 미만 0%(생활 마모), 10~30 mm·길이 모름 0.75%, 30 mm 이상 2%</li>
 *   <li>찍힘: 5 mm 미만·길이 모름 1.5%, 5 mm 이상 4%</li>
 *   <li>균열: 7.5%</li>
 *   <li>오염: 0%(청소로 해결)</li>
 * </ul>
 * 합계는 상한(보증금, 기준가의 15%)을 넘지 않는다. 기준가는 자산 대장의 취득가(설정 app.settlement.base-price).
 */
public final class SettlementRules {

    public static final String VERSION = "settlement-rules-v2";
    static final double WEAR_SCRATCH_MM = 10.0;
    static final double LONG_SCRATCH_MM = 30.0;
    static final double BIG_DENT_MM = 5.0;

    /** 비율은 만분율(basis point)로 둔다: 75 = 0.75%. */
    static final int SCRATCH_MID_BP = 75;
    static final int SCRATCH_LONG_BP = 200;
    static final int DENT_SMALL_BP = 150;
    static final int DENT_BIG_BP = 400;
    static final int CRACK_BP = 750;
    static final int CAP_BP = 1500;

    private SettlementRules() {
    }

    /** 기준가 상한(1억 원). 금액·합계를 int로 내보내므로 넘치지 않게 막는다 */
    public static final long MAX_BASE_PRICE = 100_000_000L;

    /** 기기 기준가(원). 금액은 기준가 × 비율을 천 원 단위로 반올림한다. */
    public record Pricing(long basePrice) {
        public Pricing {
            if (basePrice <= 0 || basePrice > MAX_BASE_PRICE) {
                throw new IllegalArgumentException("기준가는 1원 이상 1억 원 이하여야 합니다: " + basePrice);
            }
        }

        int amount(int bp) {
            return (int) (Math.round(basePrice * bp / 10_000.0 / 1_000.0) * 1_000L);
        }

        /** 보증금 상한(기준가의 15%) */
        public int cap() {
            return amount(CAP_BP);
        }
    }

    /** 정산 대상 흠집 묶음 하나(대표 흠집 ID, 길이·넓이는 모르면 null). */
    public record Item(String defectId, Face face, DefectClass defectClass, Double lengthMm, Double areaMm2) {
    }

    public record Line(String defectId, Face face, DefectClass defectClass, Double lengthMm, Double areaMm2,
            int amount, String rule) {
    }

    public record Outcome(List<Line> lines, int subtotal, int cap, int total, List<String> notes) {
    }

    /** 흠집 하나의 차감액과 적용한 규칙 문장(비율을 함께 적는다). */
    static Line charge(Item i, Pricing p) {
        Double len = i.lengthMm();
        return switch (i.defectClass()) {
            case SCRATCH -> {
                if (len == null) {
                    yield line(i, p, SCRATCH_MID_BP, "긁힘(길이 모름) 기본");
                }
                if (len < WEAR_SCRATCH_MM) {
                    yield line(i, p, 0, "긁힘 10mm 미만(생활 마모)");
                }
                yield len < LONG_SCRATCH_MM ? line(i, p, SCRATCH_MID_BP, "긁힘 10~30mm")
                        : line(i, p, SCRATCH_LONG_BP, "긁힘 30mm 이상");
            }
            case DENT -> dent(i, p, len);
            case CRACK -> line(i, p, CRACK_BP, "균열");
            case STAIN -> line(i, p, 0, "오염(청소로 해결)");
        };
    }

    /** 찍힘: 5mm 이상이면 큰 찍힘, 길이를 모르거나 5mm 미만이면 작은 찍힘 */
    private static Line dent(Item i, Pricing p, Double len) {
        if (len == null) {
            return line(i, p, DENT_SMALL_BP, "찍힘(길이 모름) 기본");
        }
        if (len >= BIG_DENT_MM) {
            return line(i, p, DENT_BIG_BP, "찍힘 5mm 이상");
        }
        return line(i, p, DENT_SMALL_BP, "찍힘 5mm 미만");
    }

    private static Line line(Item i, Pricing p, int bp, String rule) {
        String pct = java.math.BigDecimal.valueOf(bp, 2).stripTrailingZeros().toPlainString();
        return line(i, p.amount(bp), rule + " · 기기가의 " + pct + "%");
    }

    private static Line line(Item i, int amount, String rule) {
        return new Line(i.defectId(), i.face(), i.defectClass(), i.lengthMm(), i.areaMm2(), amount, rule);
    }

    /**
     * 전체 검사: 위치 비교로 신규(NEW)로 판정된 묶음만 넣는다.
     *
     * @param existing 이전 시점에 있던 흠집 묶음 수(참고 문장용)
     */
    public static Outcome compared(List<Item> fresh, int existing, Pricing pricing) {
        List<String> notes = new ArrayList<>();
        if (existing > 0) {
            notes.add("이전 시점에 있던 흠집 " + existing + "개는 빼고 계산");
        }
        return total(fresh.stream().map(f -> charge(f, pricing)).toList(), pricing.cap(), notes);
    }

    /**
     * 빠른 검사(또는 앞 시점이 빠른 검사): 위치 비교가 없어(기기를 놓는 자리가 매번 다름) 면·종류별 흠집 수가 앞 시점보다 늘어난 만큼만 신규로 본다.
     * 수만으로는 어느 흠집이 새것인지 알 수 없으므로, 늘어난 수만큼 그 면·종류에서 가장 짧은(길이 모름이 먼저) 묶음을 고른다. 배송 중 생긴 큰
     * 흠집을 고객에게 매기지 않도록 고객에게 유리한 쪽을 택하고, "늘어난 수"를 넘겨 매기지 않는다.
     */
    public static Outcome countDiff(List<Item> current, List<Item> previous, Pricing pricing) {
        Map<String, Integer> before = new HashMap<>();
        previous.forEach(p -> before.merge(key(p), 1, Integer::sum));
        Map<String, List<Item>> now = new java.util.LinkedHashMap<>();
        current.forEach(c -> now.computeIfAbsent(key(c), k -> new ArrayList<>()).add(c));
        List<Item> fresh = new ArrayList<>();
        int kept = 0;
        for (Map.Entry<String, List<Item>> e : now.entrySet()) {
            List<Item> items = new ArrayList<>(e.getValue());
            items.sort(Comparator.comparing((Item it) -> it.lengthMm() == null ? -1.0 : it.lengthMm()));
            int extra = Math.max(0, items.size() - before.getOrDefault(e.getKey(), 0));
            fresh.addAll(items.subList(0, extra));
            kept += items.size() - extra;
        }
        List<String> notes = new ArrayList<>();
        notes.add("위치 비교 대신 면·종류별 흠집 수가 앞 시점보다 늘어난 만큼만, 그 면·종류에서 작은 흠집부터 신규로 계산");
        if (kept > 0) {
            notes.add("앞 시점과 같은 수만큼의 흠집 " + kept + "개는 빼고 계산");
        }
        return total(fresh.stream().map(f -> charge(f, pricing)).toList(), pricing.cap(), notes);
    }

    private static String key(Item i) {
        return i.face() + "/" + i.defectClass();
    }

    private static Outcome total(List<Line> lines, int cap, List<String> notes) {
        int subtotal = (int) Math.min(Integer.MAX_VALUE, lines.stream().mapToLong(Line::amount).sum());
        int total = Math.min(subtotal, cap);
        List<String> out = new ArrayList<>(notes);
        if (total < subtotal) {
            out.add("상한(보증금, 기기가의 15%) " + String.format("%,d", cap) + "원 적용");
        }
        Map<DefectClass, Integer> zero = new EnumMap<>(DefectClass.class);
        lines.stream().filter(l -> l.amount() == 0).forEach(l -> zero.merge(l.defectClass(), 1, Integer::sum));
        if (!zero.isEmpty()) {
            out.add("차감 없는 신규 흠집 " + zero.values().stream().mapToInt(Integer::intValue).sum() + "개(생활 마모·오염)");
        }
        return new Outcome(List.copyOf(lines), subtotal, cap, total, List.copyOf(out));
    }
}
