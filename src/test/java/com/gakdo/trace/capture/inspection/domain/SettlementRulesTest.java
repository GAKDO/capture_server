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
    // 기준가 2,057,693원으로 가격 설정을 만든다 (모든 금액 계산의 기준)
    private static final SettlementRules.Pricing M2 = new SettlementRules.Pricing(2_057_693);

    // 테스트 도구: 정산할 흠집 하나(ID, 면, 종류, 길이 mm)를 만든다.
    private static Item item(String id, Face face, DefectClass c, Double len) {
        // 받은 값으로 정산 항목을 만든다 (마지막 칸은 이 테스트에서 안 쓰므로 null)
        return new Item(id, face, c, len, null);
    }

    // [상황] 전체 검사 반납에서 새 흠집 4개가 나왔다: 윗면 찍힘 6mm, 윗면 긁힘 7.5mm, 바닥 긁힘 12mm, 키보드 오염. 이전 시점부터 있던 흠집은 2개다.
    // [기대] 찍힘 5mm 이상은 기기가의 4%(82,000원), 긁힘 10~30mm는 0.75%(15,000원)이고,
    //        10mm 미만 긁힘(생활 마모)과 오염(청소로 해결)은 0원이다. 합계 97,000원, 상한 309,000원.
    //        안내에 "이전 시점에 있던 흠집 2개는 빼고 계산"과 "차감 없는 신규 흠집 2개"가 적힌다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("반납 때 새로 생긴 흠집만 규칙표 금액으로 매기고, 생활 마모 긁힘과 오염은 0원이다")
    void chargesOnlyNewDefectsByTable() {
        // 실행: 전체 검사 방식(흠집 위치로 짝지은 뒤 새것만 넘김)으로 새 흠집 4개, 이전부터 있던 흠집 2개, M2 기준가로 정산한다
        Outcome out = SettlementRules.compared(List.of(
                // 윗면 찍힘 6mm
                item("a", Face.TOP, DefectClass.DENT, 6.0),
                // 윗면 긁힘 7.5mm (10mm 미만이라 생활 마모)
                item("b", Face.TOP, DefectClass.SCRATCH, 7.5),
                // 바닥 긁힘 12mm
                item("c", Face.BOT, DefectClass.SCRATCH, 12.0),
                // 키보드 오염(길이 없음). 뒤의 2 = 이전 시점부터 있던 흠집 수
                item("d", Face.KB, DefectClass.STAIN, null)), 2, M2);

        // (도구) assertThat(목록).extracting(칸).containsExactly(...) = 목록 각 항목에서 그 칸만 뽑아, 정확히 이 값들이 이 순서로 있어야 한다는 검사
        // 확인: 항목별 금액이 찍힘 82,000 / 생활 마모 0 / 긁힘 15,000 / 오염 0이어야 한다
        assertThat(out.lines()).extracting(Line::amount).containsExactly(82_000, 0, 15_000, 0);
        // 확인: 각 항목에 적용된 규칙 문구가 이 순서대로 적혀야 한다 (고객에게 근거를 보여 주려고)
        assertThat(out.lines()).extracting(Line::rule).containsExactly("찍힘 5mm 이상 · 기기가의 4%",
                "긁힘 10mm 미만(생활 마모) · 기기가의 0%", "긁힘 10~30mm · 기기가의 0.75%", "오염(청소로 해결) · 기기가의 0%");
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 합계는 82,000 + 15,000 = 97,000원이어야 한다
        assertThat(out.total()).isEqualTo(97_000);
        // 확인: 상한(보증금, 기기가의 15%)은 309,000원이어야 한다
        assertThat(out.cap()).isEqualTo(309_000);
        // (도구) contains(...) = 목록에 이 값들이 (순서 상관없이) 들어 있어야 한다는 검사
        // 확인: 안내에 "이전 흠집 2개 제외"와 "차감 없는 신규 흠집 2개"가 적혀야 한다
        assertThat(out.notes()).contains("이전 시점에 있던 흠집 2개는 빼고 계산", "차감 없는 신규 흠집 2개(생활 마모·오염)");
    }

    // [상황] 새 깨짐(crack)이 세 면에 하나씩 생겨 합계가 462,000원이다.
    // [기대] 보증금 상한(기기가의 15%) 309,000원까지만 차감하고, 상한을 적용했다고 적는다.
    @Test
    @DisplayName("합계가 보증금 상한을 넘으면 상한까지만 차감하고 그 사실을 적는다")
    void capsAtDeposit() {
        // 실행: 윗면·바닥·키보드에 새 깨짐이 하나씩, 이전 흠집 0개로 정산한다
        Outcome out = SettlementRules.compared(List.of(
                item("a", Face.TOP, DefectClass.CRACK, null),
                item("b", Face.BOT, DefectClass.CRACK, null),
                item("c", Face.KB, DefectClass.CRACK, null)), 0, M2);

        // 확인: 상한 적용 전 합계는 462,000원이어야 한다
        assertThat(out.subtotal()).isEqualTo(462_000);
        // 확인: 실제 차감액은 상한 309,000원으로 잘려야 한다
        assertThat(out.total()).isEqualTo(309_000);
        // 확인: 상한을 적용했다는 안내가 적혀야 한다
        assertThat(out.notes()).contains("상한(보증금, 기기가의 15%) 309,000원 적용");
    }

    // [상황] 빠른 검사는 출고·반납 흠집을 위치로 짝짓지 않으므로 개수로 비교한다.
    //        앞 시점: 윗면 긁힘 1·찍힘 1. 반납: 윗면 긁힘 1·찍힘 2, 바닥 긁힘 1.
    // [기대] 늘어난 것(윗면 찍힘 1개, 바닥 긁힘 1개)만 신규로 매겨 31,000원 + 15,000원이고,
    //        "앞 시점과 같은 수만큼의 흠집 2개는 빼고 계산"이라고 적는다.
    @Test
    @DisplayName("빠른 검사는 면·종류별로 앞 시점보다 늘어난 수만큼만 신규로 매긴다")
    void quickCountsOnlyIncrease() {
        // 준비: 앞 시점 흠집 = 윗면 긁힘 1개, 윗면 찍힘 1개
        List<Item> before = List.of(item("p1", Face.TOP, DefectClass.SCRATCH, null),
                item("p2", Face.TOP, DefectClass.DENT, null));
        // 준비: 반납 흠집 = 윗면 긁힘 1개, 윗면 찍힘 2개, 바닥 긁힘 1개
        List<Item> now = List.of(item("n1", Face.TOP, DefectClass.SCRATCH, null),
                item("n2", Face.TOP, DefectClass.DENT, null), item("n3", Face.TOP, DefectClass.DENT, null),
                item("n4", Face.BOT, DefectClass.SCRATCH, null));

        // 실행: 빠른 검사 방식(면·종류별 개수 비교)으로 정산한다
        Outcome out = SettlementRules.countDiff(now, before, M2);

        // 확인: 차감 항목의 종류는 찍힘, 긁힘 순이어야 한다
        assertThat(out.lines()).extracting(Line::defectClass).containsExactly(DefectClass.DENT, DefectClass.SCRATCH);
        // 확인: 그 면은 각각 윗면, 바닥이어야 한다 (늘어난 것만 신규)
        assertThat(out.lines()).extracting(Line::face).containsExactly(Face.TOP, Face.BOT);
        // 확인: 합계는 찍힘 31,000원 + 긁힘 15,000원이어야 한다
        assertThat(out.total()).isEqualTo(31_000 + 15_000);
        // 확인: 앞 시점과 같은 수만큼 2개를 뺐다는 안내가 적혀야 한다
        assertThat(out.notes()).contains("앞 시점과 같은 수만큼의 흠집 2개는 빼고 계산");
    }

    // [상황] 빠른 검사에서 반납 흠집 수가 앞 시점과 같다.
    // [기대] 차감 항목이 없고 합계 0원이다.
    @Test
    @DisplayName("빠른 검사에서 흠집 수가 같거나 줄면 차감이 없다")
    void quickNoIncreaseNoCharge() {
        // 준비: 윗면 긁힘 1개짜리 목록 (앞 시점과 반납 둘 다에 쓴다)
        List<Item> same = List.of(item("x", Face.TOP, DefectClass.SCRATCH, null));
        // 실행: 반납과 앞 시점을 같은 목록으로 놓고 개수 비교 정산을 한다
        Outcome out = SettlementRules.countDiff(same, same, M2);
        // (도구) isEmpty() = 목록이 비어 있어야 한다는 검사
        // 확인: 차감 항목이 하나도 없어야 한다
        assertThat(out.lines()).isEmpty();
        // (도구) isZero() = 값이 0이어야 한다는 검사
        // 확인: 합계가 0원이어야 한다
        assertThat(out.total()).isZero();
    }

    // [상황] 빠른 검사에서 윗면 찍힘이 1개(6mm) → 2개(6mm, 2mm)로 늘었다. 어느 쪽이 새것인지 알 수 없다.
    // [기대] 고객에게 유리하게 작은 흠집(2mm)을 신규로 보고 31,000원만 차감한다.
    @Test
    @DisplayName("빠른 검사에서 같은 면·종류의 수만 늘면 고객에게 유리하게 작은 흠집부터 신규로 본다")
    void quickPicksSmallestWhenCountRises() {
        // 준비: 앞 시점 = 윗면 찍힘 6mm 1개
        List<Item> before = List.of(item("p1", Face.TOP, DefectClass.DENT, 6.0));
        // 준비: 반납 = 윗면 찍힘 6mm("big")와 2mm("small") 2개
        List<Item> now = List.of(item("big", Face.TOP, DefectClass.DENT, 6.0),
                item("small", Face.TOP, DefectClass.DENT, 2.0));

        // 실행: 개수 비교 정산을 한다 (찍힘이 1개 늘었음)
        Outcome out = SettlementRules.countDiff(now, before, M2);

        // 확인: 신규로 잡힌 흠집은 작은 "small" 하나여야 한다 (고객에게 유리하게)
        assertThat(out.lines()).extracting(Line::defectId).containsExactly("small");
        // 확인: 차감액은 작은 찍힘 금액 31,000원이어야 한다
        assertThat(out.total()).isEqualTo(31_000);
    }

    // [상황] 같은 깨짐 하나를 기준가 2,057,693원 기기와 1,000,000원 기기에 매긴다.
    // [기대] 각각 154,000원, 75,000원이고 싼 기기의 상한은 150,000원이다(기종이 바뀌어도 기준가만 바꾸면 됨).
    @Test
    @DisplayName("금액은 기준가 × 비율을 천 원 단위로 반올림하고, 기종이 바뀌면 기준가만 바꾸면 된다")
    void amountsScaleWithBasePrice() {
        // 준비: 기준가 100만 원짜리 싼 기기 가격 설정을 만든다
        SettlementRules.Pricing cheap = new SettlementRules.Pricing(1_000_000);
        // 준비: 윗면 깨짐 하나를 만든다
        Item crack = item("x", Face.TOP, DefectClass.CRACK, null);
        // 실행+확인: M2 기기에서 이 깨짐은 154,000원이어야 한다
        assertThat(SettlementRules.charge(crack, M2).amount()).isEqualTo(154_000);
        // 실행+확인: 100만 원 기기에서는 75,000원이어야 한다 (기준가에 비례)
        assertThat(SettlementRules.charge(crack, cheap).amount()).isEqualTo(75_000);
        // 확인: 100만 원 기기의 상한은 15%인 150,000원이어야 한다
        assertThat(cheap.cap()).isEqualTo(150_000);
    }

    // [상황] 기준가를 0원, 200억 원처럼 말이 안 되는 값으로 설정한다.
    // [기대] 설정을 거부한다(금액 계산이 넘치지 않게). 허용 최대 기준가(1억 원)의 상한은 1,500만 원이다.
    @Test
    @DisplayName("기준가가 0 이하이거나 1억 원을 넘으면 설정을 거부해 금액이 넘치지 않는다")
    void rejectsOutOfRangeBasePrice() {
        // (도구) assertThatThrownBy(() -> 코드).isInstanceOf(종류) = 그 코드를 실행하면 이 종류의 예외가 나야 한다는 검사
        // 실행+확인: 기준가 0원으로 만들면 잘못된 값 예외가 나야 한다
        assertThatThrownBy(() -> new SettlementRules.Pricing(0)).isInstanceOf(IllegalArgumentException.class);
        // 실행+확인: 기준가 200억 원으로 만들어도 잘못된 값 예외가 나야 한다
        assertThatThrownBy(() -> new SettlementRules.Pricing(20_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
        // 실행+확인: 허용 최대 기준가로 만든 설정의 상한은 1,500만 원이어야 한다 (최댓값은 받아들임)
        assertThat(new SettlementRules.Pricing(SettlementRules.MAX_BASE_PRICE).cap()).isEqualTo(15_000_000);
    }
}
