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

    // 지울 시점 ID
    private static final String CP = "h40b9AN9xMQhXRHrOyJWB";

    // (도구) mock(클래스) = 진짜 DB 대신 쓰는 가짜 객체(목). 불린 기록을 남긴다
    // 준비: 지우기용 DB 저장소를 가짜로 만든다
    private final PurgeStore store = mock(PurgeStore.class);
    // 준비: MinIO 대신 메모리 사진 저장소를 만든다
    private final FakePhotoStorage storage = new FakePhotoStorage();
    // 준비: 시험할 대상인 "시점 지움" 이벤트 리스너를 위 둘과 JSON 변환기로 만든다
    private final PurgeEventListener listener =
            new PurgeEventListener(new CheckpointPurgeService(store), storage, new ObjectMapper());

    // 테스트 도구: 시점 지움 이벤트 JSON을 만든다. 시리얼을 바꿀 수 있고, 앞으로 생길 모르는 칸(futureField)도 일부러 넣는다.
    private static byte[] event(String serial) {
        // 이벤트 JSON 글자를 만들어 바이트로 돌려준다 (렌탈·시점·기기 ID, 시리얼, 모르는 칸 futureField 포함)
        return ("{\"schemaVersion\":\"1.0\",\"eventId\":\"Pg2pQx7LmVb0RtYs9WkDe\",\"occurredAt\":\"2026-11-04T16:20:00+09:00\","
                + "\"rentalId\":\"Ws0HLv6QRbDILPWOcz5I2\",\"checkpointId\":\"" + CP + "\",\"checkpointType\":\"OUT\","
                + "\"deviceId\":\"Nq8sUe2ZpW4cYb6Td1MfA\",\"serial\":\"" + serial + "\",\"futureField\":1}")
                .getBytes(StandardCharsets.UTF_8);
    }

    // [상황] 시리얼 TEST-0002 시점을 지우라는 이벤트가 온다. 그 시점 원본 사진·펼친 이미지와, 상관없는 다른 사진이 저장소에 있다.
    // [기대] 그 시점 DB 행을 지우고 그 시점 원본·펼친 이미지만 지운다. 다른 사진은 남는다. 모르는 칸이 있어도 처리한다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("시험 시점 지움 이벤트를 받으면 그 시점 행을 지우고 원본 사진·펼친 이미지 객체도 지운다")
    void purgesRowsAndObjects() {
        // 준비: 이 시점의 원본 사진을 저장소에 넣는다
        storage.put("captures/a.jpg", "x");
        // 준비: 이 시점의 펼친 이미지를 넣는다
        storage.put("rectified/a.jpg", "y");
        // 준비: 상관없는 다른 사진도 넣는다 (지워지면 안 됨)
        storage.put("captures/other.jpg", "z");
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 이 시점의 파일 키를 물으면 원본 a.jpg와 펼친 이미지 a.jpg를 돌려주게 한다
        when(store.keysOf(CP)).thenReturn(new PurgeStore.Keys(List.of("captures/a.jpg"), List.of("rectified/a.jpg")));

        // 실행: 시험 시리얼 TEST-0002로 된 지움 이벤트를 처리한다
        listener.handle(event("TEST-0002"));

        // (도구) verify(가짜).메서드(값) = 그 메서드가 이 값으로 불렸어야 한다는 검사
        // 확인: 이 시점의 DB 행 지우기가 불렸어야 한다
        verify(store).deleteCheckpoint(CP);
        // (도구) assertThat(값).isFalse() = 값이 거짓(false)이어야 한다는 검사
        // 확인: 원본 사진이 지워졌어야 한다
        assertThat(storage.exists("captures/a.jpg")).isFalse();
        // 확인: 펼친 이미지도 지워졌어야 한다
        assertThat(storage.exists("rectified/a.jpg")).isFalse();
        // 확인: 상관없는 사진은 남아 있어야 한다
        assertThat(storage.exists("captures/other.jpg")).isTrue();
    }

    // [상황] 진짜 기기 시리얼(TEST-로 시작하지 않음)로 지우라는 이벤트가 온다.
    // [기대] 아무것도 지우지 않고 실패함(DLX)으로 보낸다.
    @Test
    @DisplayName("시리얼이 TEST-가 아니면 아무것도 지우지 않고 DLX로 보낸다")
    void refusesRealSerial() {
        // 준비: 진짜 기기 시리얼로 된 지움 이벤트를 만든다
        var real = event("C02REAL0001");
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 그 이벤트를 처리하면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle(real))
                // 확인: "다시 시도하지 말고 실패함(DLX)으로" 예외여야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // (도구) never() = 한 번도 불리지 않았어야 한다는 조건. any() = 아무 값
        // 확인: DB 행은 하나도 지우지 않았어야 한다
        verify(store, never()).deleteCheckpoint(any());
    }

    // [상황] JSON이 깨진 메시지가 온다.
    // [기대] 아무것도 지우지 않고 실패함(DLX)으로 보낸다.
    @Test
    @DisplayName("JSON이 깨진 메시지는 DLX로 보낸다")
    void rejectsBrokenJson() {
        // 준비: 깨진 JSON("{")을 만든다
        byte[] broken = "{".getBytes(StandardCharsets.UTF_8);
        // 실행+확인: 그 메시지를 처리하면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle(broken))
                // 확인: DLX로 보내는 예외여야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 확인: 아무것도 지우지 않았어야 한다
        verify(store, never()).deleteCheckpoint(any());
    }

    // [상황] 이벤트의 렌탈이 실제로 그 시점 세트를 가진 렌탈과 다르다.
    // [기대] 다른 렌탈 데이터를 지우지 않도록 거절하고 실패함(DLX)으로 보낸다.
    @Test
    @DisplayName("이벤트의 렌탈이 저장된 세트의 렌탈과 다르면 지우지 않고 DLX로 보낸다")
    void refusesMismatchedRental() {
        // 준비: "이 시점이 다른 렌탈 것이냐?"에 true로 답하게 한다
        when(store.belongsToOtherRental("Ws0HLv6QRbDILPWOcz5I2", CP)).thenReturn(true);
        // 준비: 시험 시리얼로 된 지움 이벤트를 만든다
        var test = event("TEST-0002");
        // 실행+확인: 처리하면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle(test))
                // 확인: DLX로 보내는 예외여야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 확인: 다른 렌탈 데이터는 지우지 않았어야 한다
        verify(store, never()).deleteCheckpoint(any());
    }
}
