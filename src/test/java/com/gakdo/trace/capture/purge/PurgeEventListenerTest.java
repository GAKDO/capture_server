package com.gakdo.trace.capture.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.capture.purge.application.CheckpointPurgeService;
import com.gakdo.trace.capture.purge.application.PurgeStore;
import com.gakdo.trace.capture.purge.infra.PurgeEventListener;
import com.gakdo.trace.capture.support.FakePhotoStorage;

class PurgeEventListenerTest {

    private static final String CP = "h40b9AN9xMQhXRHrOyJWB";

    private final PurgeStore store = mock(PurgeStore.class);
    private final FakePhotoStorage storage = new FakePhotoStorage();
    private final PurgeEventListener listener =
            new PurgeEventListener(new CheckpointPurgeService(store), storage, new ObjectMapper());

    private static byte[] event(String serial) {
        return ("{\"schemaVersion\":\"1.0\",\"eventId\":\"Pg2pQx7LmVb0RtYs9WkDe\",\"occurredAt\":\"2026-11-04T16:20:00+09:00\","
                + "\"rentalId\":\"Ws0HLv6QRbDILPWOcz5I2\",\"checkpointId\":\"" + CP + "\",\"checkpointType\":\"OUT\","
                + "\"deviceId\":\"Nq8sUe2ZpW4cYb6Td1MfA\",\"serial\":\"" + serial + "\",\"futureField\":1}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("시험 시점 지움 이벤트를 받으면 그 시점 행을 지우고 원본 사진·펼친 이미지 객체도 지운다")
    void purgesRowsAndObjects() {
        storage.put("captures/a.jpg", "x");
        storage.put("rectified/a.jpg", "y");
        storage.put("captures/other.jpg", "z");
        when(store.keysOf(CP)).thenReturn(new PurgeStore.Keys(List.of("captures/a.jpg"), List.of("rectified/a.jpg")));

        listener.handle(event("TEST-0002"));

        verify(store).deleteCheckpoint(CP);
        assertThat(storage.exists("captures/a.jpg")).isFalse();
        assertThat(storage.exists("rectified/a.jpg")).isFalse();
        assertThat(storage.exists("captures/other.jpg")).isTrue();
    }

    @Test
    @DisplayName("시리얼이 TEST-가 아니면 아무것도 지우지 않고 DLX로 보낸다")
    void refusesRealSerial() {
        assertThatThrownBy(() -> listener.handle(event("C02REAL0001")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).deleteCheckpoint(any());
    }

    @Test
    @DisplayName("JSON이 깨진 메시지는 DLX로 보낸다")
    void rejectsBrokenJson() {
        assertThatThrownBy(() -> listener.handle("{".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).deleteCheckpoint(any());
    }

    @Test
    @DisplayName("이벤트의 렌탈이 저장된 세트의 렌탈과 다르면 지우지 않고 DLX로 보낸다")
    void refusesMismatchedRental() {
        when(store.belongsToOtherRental("Ws0HLv6QRbDILPWOcz5I2", CP)).thenReturn(true);
        assertThatThrownBy(() -> listener.handle(event("TEST-0002")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).deleteCheckpoint(any());
    }
}
