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
        List<Pin> pins = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pins.add(new Pin("d" + i, Face.TOP, i, i, i % newEvery == 0));
        }
        return pins;
    }

    // [상황] 핀이 정확히 500개다.
    // [기대] 하나도 자르지 않고 그대로 싣는다.
    @Test
    @DisplayName("핀이 500개면 그대로 싣는다")
    void exactlyMaxIsKept() {
        List<Pin> pins = pins(500, 3);
        assertThat(GradingService.limit(pins, GradingService.MAX_PINS)).isEqualTo(pins);
    }

    // [상황] 핀이 501개다.
    // [기대] 500개만 싣고, 새 흠집 핀은 하나도 잃지 않으며 목록 앞쪽에 먼저 온다.
    @Test
    @DisplayName("핀이 501개면 500개만 싣고, 새 흠집 핀을 먼저 남긴다")
    void overMaxKeepsNewFirst() {
        List<Pin> pins = pins(501, 3);
        List<Pin> kept = GradingService.limit(pins, GradingService.MAX_PINS);
        assertThat(kept).hasSize(500);
        long newCount = pins.stream().filter(Pin::isNew).count();
        assertThat(kept.stream().filter(Pin::isNew).count()).isEqualTo(newCount);
        assertThat(kept.subList(0, (int) newCount)).allMatch(Pin::isNew);
    }
}
