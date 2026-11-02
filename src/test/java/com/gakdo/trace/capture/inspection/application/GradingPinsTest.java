package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.inspection.application.CheckpointGradedEvent.Pin;

class GradingPinsTest {

    private static List<Pin> pins(int count, int newEvery) {
        List<Pin> pins = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pins.add(new Pin("d" + i, Face.TOP, i, i, i % newEvery == 0));
        }
        return pins;
    }

    @Test
    @DisplayName("핀이 500개면 그대로 싣는다")
    void exactlyMaxIsKept() {
        List<Pin> pins = pins(500, 3);
        assertThat(GradingService.limit(pins, GradingService.MAX_PINS)).isEqualTo(pins);
    }

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
