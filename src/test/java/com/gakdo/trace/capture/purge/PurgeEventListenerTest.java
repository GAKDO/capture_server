// 이 파일이 확인하는 것: 시험 데이터 지우기(contracts #25). 관리자가 시험용 시점을 지우면 rental-server가 이벤트를 보내고,
// capture-server는 그 시점의 촬영·검사 행과 MinIO 사진을 지운다. 진짜 기기를 실수로 지우지 않도록 TEST- 시리얼만 허용한다.

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

    // 테스트 도구: 시점 지움 이벤트 JSON을 만든다. 시리얼을 바꿀 수 있고, 앞으로 생길 모르는 칸(futureField)도 일부러 넣는다.
    private static byte[] event(String serial) {
        return ("{\"schemaVersion\":\"1.0\",\"eventId\":\"Pg2pQx7LmVb0RtYs9WkDe\",\"occurredAt\":\"2026-11-04T16:20:00+09:00\","
                + "\"rentalId\":\"Ws0HLv6QRbDILPWOcz5I2\",\"checkpointId\":\"" + CP + "\",\"checkpointType\":\"OUT\","
                + "\"deviceId\":\"Nq8sUe2ZpW4cYb6Td1MfA\",\"serial\":\"" + serial + "\",\"futureField\":1}")
                .getBytes(StandardCharsets.UTF_8);
    }

    // [상황] 시리얼 TEST-0002 시점을 지우라는 이벤트가 온다. 그 시점 원본 사진·펼친 이미지와, 상관없는 다른 사진이 저장소에 있다.
    // [기대] 그 시점 DB 행을 지우고 그 시점 원본·펼친 이미지만 지운다. 다른 사진은 남는다. 모르는 칸이 있어도 처리한다.
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

    // [상황] 진짜 기기 시리얼(TEST-로 시작하지 않음)로 지우라는 이벤트가 온다.
    // [기대] 아무것도 지우지 않고 실패함(DLX)으로 보낸다.
    @Test
    @DisplayName("시리얼이 TEST-가 아니면 아무것도 지우지 않고 DLX로 보낸다")
    void refusesRealSerial() {
        var real = event("C02REAL0001");
        assertThatThrownBy(() -> listener.handle(real))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).deleteCheckpoint(any());
    }

    // [상황] JSON이 깨진 메시지가 온다.
    // [기대] 아무것도 지우지 않고 실패함(DLX)으로 보낸다.
    @Test
    @DisplayName("JSON이 깨진 메시지는 DLX로 보낸다")
    void rejectsBrokenJson() {
        byte[] broken = "{".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> listener.handle(broken))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).deleteCheckpoint(any());
    }

    // [상황] 이벤트의 렌탈이 실제로 그 시점 세트를 가진 렌탈과 다르다.
    // [기대] 다른 렌탈 데이터를 지우지 않도록 거절하고 실패함(DLX)으로 보낸다.
    @Test
    @DisplayName("이벤트의 렌탈이 저장된 세트의 렌탈과 다르면 지우지 않고 DLX로 보낸다")
    void refusesMismatchedRental() {
        when(store.belongsToOtherRental("Ws0HLv6QRbDILPWOcz5I2", CP)).thenReturn(true);
        var test = event("TEST-0002");
        assertThatThrownBy(() -> listener.handle(test))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(store, never()).deleteCheckpoint(any());
    }
}
