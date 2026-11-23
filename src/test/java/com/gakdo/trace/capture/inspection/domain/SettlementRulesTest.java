// 이 파일이 확인하는 것: 반납 정산 규칙표(settlement-rules-v2, 11/10). 반납 때 새로 생긴 흠집에 얼마를 차감할지 정한다.
// 금액은 기기 기준가(취득가)에 흠집 종류·크기별 비율을 곱해 천 원 단위로 반올림하고, 합계는 보증금(기기가의 15%)을 넘지 않는다.
// 시험의 예시 기기는 발표에 쓴 MacBook Air M2 16GB(기준가 2,057,693원)다.

package com.gakdo.trace.capture.inspection.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.SettlementRules.Item;
import com.gakdo.trace.capture.inspection.domain.SettlementRules.Line;
import com.gakdo.trace.capture.inspection.domain.SettlementRules.Outcome;

/** 반납 정산 규칙표 settlement-rules-v2(11/10, 기기가 대비 비율). Docker 없이 돈다. */
class SettlementRulesTest {

    /** 발표 예시 기기: M2 16GB 취득가 */
    private static final SettlementRules.Pricing M2 = new SettlementRules.Pricing(2_057_693);

    // 테스트 도구: 정산할 흠집 하나(ID, 면, 종류, 길이 mm)를 만든다.
    private static Item item(String id, Face face, DefectClass c, Double len) {
        return new Item(id, face, c, len, null);
    }

    // [상황] 전체 검사 반납에서 새 흠집 4개가 나왔다: 윗면 찍힘 6mm, 윗면 긁힘 7.5mm, 바닥 긁힘 12mm, 키보드 오염. 이전 시점부터 있던 흠집은 2개다.
    // [기대] 찍힘 5mm 이상은 기기가의 4%(82,000원), 긁힘 10~30mm는 0.75%(15,000원)이고,
    //        10mm 미만 긁힘(생활 마모)과 오염(청소로 해결)은 0원이다. 합계 97,000원, 상한 309,000원.
    //        안내에 "이전 시점에 있던 흠집 2개는 빼고 계산"과 "차감 없는 신규 흠집 2개"가 적힌다.
    @Test
    @DisplayName("반납 때 새로 생긴 흠집만 규칙표 금액으로 매기고, 생활 마모 긁힘과 오염은 0원이다")
    void chargesOnlyNewDefectsByTable() {
        Outcome out = SettlementRules.compared(List.of(
                item("a", Face.TOP, DefectClass.DENT, 6.0),
                item("b", Face.TOP, DefectClass.SCRATCH, 7.5),
                item("c", Face.BOT, DefectClass.SCRATCH, 12.0),
                item("d", Face.KB, DefectClass.STAIN, null)), 2, M2);

        assertThat(out.lines()).extracting(Line::amount).containsExactly(82_000, 0, 15_000, 0);
        assertThat(out.lines()).extracting(Line::rule).containsExactly("찍힘 5mm 이상 · 기기가의 4%",
                "긁힘 10mm 미만(생활 마모) · 기기가의 0%", "긁힘 10~30mm · 기기가의 0.75%", "오염(청소로 해결) · 기기가의 0%");
        assertThat(out.total()).isEqualTo(97_000);
        assertThat(out.cap()).isEqualTo(309_000);
        assertThat(out.notes()).contains("이전 시점에 있던 흠집 2개는 빼고 계산", "차감 없는 신규 흠집 2개(생활 마모·오염)");
    }

    // [상황] 새 깨짐(crack)이 세 면에 하나씩 생겨 합계가 462,000원이다.
    // [기대] 보증금 상한(기기가의 15%) 309,000원까지만 차감하고, 상한을 적용했다고 적는다.
    @Test
    @DisplayName("합계가 보증금 상한을 넘으면 상한까지만 차감하고 그 사실을 적는다")
    void capsAtDeposit() {
        Outcome out = SettlementRules.compared(List.of(
                item("a", Face.TOP, DefectClass.CRACK, null),
                item("b", Face.BOT, DefectClass.CRACK, null),
                item("c", Face.KB, DefectClass.CRACK, null)), 0, M2);

        assertThat(out.subtotal()).isEqualTo(462_000);
        assertThat(out.total()).isEqualTo(309_000);
        assertThat(out.notes()).contains("상한(보증금, 기기가의 15%) 309,000원 적용");
    }

    // [상황] 빠른 검사는 출고·반납 흠집을 위치로 짝짓지 않으므로 개수로 비교한다.
    //        앞 시점: 윗면 긁힘 1·찍힘 1. 반납: 윗면 긁힘 1·찍힘 2, 바닥 긁힘 1.
    // [기대] 늘어난 것(윗면 찍힘 1개, 바닥 긁힘 1개)만 신규로 매겨 31,000원 + 15,000원이고,
    //        "앞 시점과 같은 수만큼의 흠집 2개는 빼고 계산"이라고 적는다.
    @Test
    @DisplayName("빠른 검사는 면·종류별로 앞 시점보다 늘어난 수만큼만 신규로 매긴다")
    void quickCountsOnlyIncrease() {
        List<Item> before = List.of(item("p1", Face.TOP, DefectClass.SCRATCH, null),
                item("p2", Face.TOP, DefectClass.DENT, null));
        List<Item> now = List.of(item("n1", Face.TOP, DefectClass.SCRATCH, null),
                item("n2", Face.TOP, DefectClass.DENT, null), item("n3", Face.TOP, DefectClass.DENT, null),
                item("n4", Face.BOT, DefectClass.SCRATCH, null));

        Outcome out = SettlementRules.countDiff(now, before, M2);

        assertThat(out.lines()).extracting(Line::defectClass).containsExactly(DefectClass.DENT, DefectClass.SCRATCH);
        assertThat(out.lines()).extracting(Line::face).containsExactly(Face.TOP, Face.BOT);
        assertThat(out.total()).isEqualTo(31_000 + 15_000);
        assertThat(out.notes()).contains("앞 시점과 같은 수만큼의 흠집 2개는 빼고 계산");
    }

    // [상황] 빠른 검사에서 반납 흠집 수가 앞 시점과 같다.
    // [기대] 차감 항목이 없고 합계 0원이다.
    @Test
    @DisplayName("빠른 검사에서 흠집 수가 같거나 줄면 차감이 없다")
    void quickNoIncreaseNoCharge() {
        List<Item> same = List.of(item("x", Face.TOP, DefectClass.SCRATCH, null));
        Outcome out = SettlementRules.countDiff(same, same, M2);
        assertThat(out.lines()).isEmpty();
        assertThat(out.total()).isZero();
    }

    // [상황] 빠른 검사에서 윗면 찍힘이 1개(6mm) → 2개(6mm, 2mm)로 늘었다. 어느 쪽이 새것인지 알 수 없다.
    // [기대] 고객에게 유리하게 작은 흠집(2mm)을 신규로 보고 31,000원만 차감한다.
    @Test
    @DisplayName("빠른 검사에서 같은 면·종류의 수만 늘면 고객에게 유리하게 작은 흠집부터 신규로 본다")
    void quickPicksSmallestWhenCountRises() {
        List<Item> before = List.of(item("p1", Face.TOP, DefectClass.DENT, 6.0));
        List<Item> now = List.of(item("big", Face.TOP, DefectClass.DENT, 6.0),
                item("small", Face.TOP, DefectClass.DENT, 2.0));

        Outcome out = SettlementRules.countDiff(now, before, M2);

        assertThat(out.lines()).extracting(Line::defectId).containsExactly("small");
        assertThat(out.total()).isEqualTo(31_000);
    }

    // [상황] 같은 깨짐 하나를 기준가 2,057,693원 기기와 1,000,000원 기기에 매긴다.
    // [기대] 각각 154,000원, 75,000원이고 싼 기기의 상한은 150,000원이다(기종이 바뀌어도 기준가만 바꾸면 됨).
    @Test
    @DisplayName("금액은 기준가 × 비율을 천 원 단위로 반올림하고, 기종이 바뀌면 기준가만 바꾸면 된다")
    void amountsScaleWithBasePrice() {
        SettlementRules.Pricing cheap = new SettlementRules.Pricing(1_000_000);
        Item crack = item("x", Face.TOP, DefectClass.CRACK, null);
        assertThat(SettlementRules.charge(crack, M2).amount()).isEqualTo(154_000);
        assertThat(SettlementRules.charge(crack, cheap).amount()).isEqualTo(75_000);
        assertThat(cheap.cap()).isEqualTo(150_000);
    }

    // [상황] 기준가를 0원, 200억 원처럼 말이 안 되는 값으로 설정한다.
    // [기대] 설정을 거부한다(금액 계산이 넘치지 않게). 허용 최대 기준가(1억 원)의 상한은 1,500만 원이다.
    @Test
    @DisplayName("기준가가 0 이하이거나 1억 원을 넘으면 설정을 거부해 금액이 넘치지 않는다")
    void rejectsOutOfRangeBasePrice() {
        assertThatThrownBy(() -> new SettlementRules.Pricing(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementRules.Pricing(20_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SettlementRules.Pricing(SettlementRules.MAX_BASE_PRICE).cap()).isEqualTo(15_000_000);
    }
}
