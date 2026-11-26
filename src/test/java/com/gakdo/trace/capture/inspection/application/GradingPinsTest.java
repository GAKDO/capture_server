// 이 파일이 확인하는 것: 등급 결과에 싣는 흠집 핀(사진 위 표시 점)의 최대 개수 500개 제한.
// 핀이 너무 많아 응답·이벤트가 커지지 않게 자르되, 고객에게 중요한 "새 흠집" 핀은 먼저 남긴다.

package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.inspection.application.CheckpointGradedEvent.Pin;

class GradingPinsTest {

    // 테스트 도구: 핀을 count개 만들고, newEvery개마다 하나를 새 흠집 핀으로 표시한다.
    private static List<Pin> pins(int count, int newEvery) {
        // 핀을 담을 빈 목록을 만든다
        List<Pin> pins = new ArrayList<>();
        // count번 되풀이한다
        for (int i = 0; i < count; i++) {
            // 흠집 ID d0·d1…, 윗면(TOP), 좌표 (i, i), i가 newEvery의 배수면 새 흠집으로 표시한 핀을 넣는다
            pins.add(new Pin("d" + i, Face.TOP, i, i, i % newEvery == 0));
        }
        // 만든 핀 목록을 돌려준다
        return pins;
    }

    // [상황] 핀이 정확히 500개다.
    // [기대] 하나도 자르지 않고 그대로 싣는다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("핀이 500개면 그대로 싣는다")
    void exactlyMaxIsKept() {
        // 준비: 핀 500개(3개마다 새 흠집)를 만든다
        List<Pin> pins = pins(500, 3);
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 실행+확인: 최대 500개로 자르면 원래 목록과 똑같아야 한다 (자를 것이 없음)
        assertThat(GradingService.limit(pins, GradingService.MAX_PINS)).isEqualTo(pins);
    }

    // [상황] 핀이 501개다.
    // [기대] 500개만 싣고, 새 흠집 핀은 하나도 잃지 않으며 목록 앞쪽에 먼저 온다.
    @Test
    @DisplayName("핀이 501개면 500개만 싣고, 새 흠집 핀을 먼저 남긴다")
    void overMaxKeepsNewFirst() {
        // 준비: 핀 501개(3개마다 새 흠집)를 만든다
        List<Pin> pins = pins(501, 3);
        // 실행: 최대 500개로 자른다
        List<Pin> kept = GradingService.limit(pins, GradingService.MAX_PINS);
        // 확인: 남은 핀은 500개여야 한다 (hasSize = 개수 검사)
        assertThat(kept).hasSize(500);
        // 준비: 원래 목록의 새 흠집 핀 개수를 센다
        long newCount = pins.stream().filter(Pin::isNew).count();
        // 확인: 자른 뒤에도 새 흠집 핀 개수가 그대로여야 한다 (하나도 잃지 않음)
        assertThat(kept.stream().filter(Pin::isNew).count()).isEqualTo(newCount);
        // 확인: 목록 앞쪽 newCount개가 모두 새 흠집 핀이어야 한다 (allMatch = 전부 조건을 만족)
        assertThat(kept.subList(0, (int) newCount)).allMatch(Pin::isNew);
    }
}
