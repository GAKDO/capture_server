package com.gakdo.trace.capture.inspection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.SettlementRules.Item;
import com.gakdo.trace.capture.inspection.domain.SettlementRules.Line;
import com.gakdo.trace.capture.inspection.domain.SettlementRules.Outcome;

/** 반납 정산 규칙표 settlement-rules-v1(11/10). Docker 없이 돈다. */
class SettlementRulesTest {

    private static Item item(String id, Face face, DefectClass c, Double len) {
        return new Item(id, face, c, len, null);
    }

    @Test
    @DisplayName("반납 때 새로 생긴 흠집만 규칙표 금액으로 매기고, 생활 마모 긁힘과 오염은 0원이다")
    void chargesOnlyNewDefectsByTable() {
        Outcome out = SettlementRules.compared(List.of(
                item("a", Face.TOP, DefectClass.DENT, 6.0),
                item("b", Face.TOP, DefectClass.SCRATCH, 7.5),
                item("c", Face.BOT, DefectClass.SCRATCH, 12.0),
                item("d", Face.KB, DefectClass.STAIN, null)), 2, 300_000);

        assertThat(out.lines()).extracting(Line::amount).containsExactly(80_000, 0, 15_000, 0);
        assertThat(out.lines()).extracting(Line::rule)
                .containsExactly("찍힘 5mm 이상", "긁힘 10mm 미만(생활 마모)", "긁힘 10~30mm", "오염(청소로 해결)");
        assertThat(out.total()).isEqualTo(95_000);
        assertThat(out.notes()).contains("이전 시점에 있던 흠집 2개는 빼고 계산", "차감 없는 신규 흠집 2개(생활 마모·오염)");
    }

    @Test
    @DisplayName("합계가 보증금 상한을 넘으면 상한까지만 차감하고 그 사실을 적는다")
    void capsAtDeposit() {
        Outcome out = SettlementRules.compared(List.of(
                item("a", Face.TOP, DefectClass.CRACK, null),
                item("b", Face.BOT, DefectClass.CRACK, null)), 0, 200_000);

        assertThat(out.subtotal()).isEqualTo(300_000);
        assertThat(out.total()).isEqualTo(200_000);
        assertThat(out.notes()).contains("상한(보증금) 200000원 적용");
    }

    @Test
    @DisplayName("빠른 검사는 면·종류별로 앞 시점보다 늘어난 수만큼만 신규로 매긴다")
    void quickCountsOnlyIncrease() {
        List<Item> before = List.of(item("p1", Face.TOP, DefectClass.SCRATCH, null),
                item("p2", Face.TOP, DefectClass.DENT, null));
        List<Item> now = List.of(item("n1", Face.TOP, DefectClass.SCRATCH, null),
                item("n2", Face.TOP, DefectClass.DENT, null), item("n3", Face.TOP, DefectClass.DENT, null),
                item("n4", Face.BOT, DefectClass.SCRATCH, null));

        Outcome out = SettlementRules.countDiff(now, before, 300_000);

        assertThat(out.lines()).extracting(Line::defectClass).containsExactly(DefectClass.DENT, DefectClass.SCRATCH);
        assertThat(out.lines()).extracting(Line::face).containsExactly(Face.TOP, Face.BOT);
        assertThat(out.total()).isEqualTo(30_000 + 15_000);
        assertThat(out.notes()).contains("앞 시점과 같은 수만큼의 흠집 2개는 빼고 계산");
    }

    @Test
    @DisplayName("빠른 검사에서 흠집 수가 같거나 줄면 차감이 없다")
    void quickNoIncreaseNoCharge() {
        List<Item> same = List.of(item("x", Face.TOP, DefectClass.SCRATCH, null));
        Outcome out = SettlementRules.countDiff(same, same, 300_000);
        assertThat(out.lines()).isEmpty();
        assertThat(out.total()).isZero();
    }

    @Test
    @DisplayName("빠른 검사에서 같은 면·종류의 수만 늘면 고객에게 유리하게 작은 흠집부터 신규로 본다")
    void quickPicksSmallestWhenCountRises() {
        List<Item> before = List.of(item("p1", Face.TOP, DefectClass.DENT, 6.0));
        List<Item> now = List.of(item("big", Face.TOP, DefectClass.DENT, 6.0),
                item("small", Face.TOP, DefectClass.DENT, 2.0));

        Outcome out = SettlementRules.countDiff(now, before, 300_000);

        assertThat(out.lines()).extracting(Line::defectId).containsExactly("small");
        assertThat(out.total()).isEqualTo(30_000);
    }
}
