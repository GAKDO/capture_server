// 이 파일이 확인하는 것: RabbitMQ로 받은 검사 메시지(탐지 결과 등)를 처리하다 실패했을 때의 재시도 규칙.
// 메시지 형식 자체가 틀린 것은 다시 해도 안 되므로 바로 실패함(DLQ)으로 보내고,
// 잠깐 DB가 끊긴 것 같은 일시적 실패는 정해진 횟수만큼 다시 해 본다.

package com.gakdo.trace.capture.inspection.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer.Target;
import org.springframework.retry.support.RetryTemplate;

class InspectAmqpConfigTest {

    // 테스트 도구: 운영과 같은 재시도 설정(최대 3번)을 적용한 재시도 틀을 만든다.
    private RetryTemplate listenerTemplate() {
        RetryTemplate template = new RetryTemplate();
        new InspectAmqpConfig().noRetryOnReject(3).customize(Target.LISTENER, template);
        return template;
    }

    // [상황] 메시지 형식 오류로 거절했다(그 거절이 다른 예외 안에 감싸여 있어도).
    // [기대] 다시 시도하지 않고 딱 한 번만 처리한 뒤 실패함(DLQ)으로 보낸다.
    @Test
    @DisplayName("형식 오류로 거절한 메시지(원인 사슬 안에 있어도)는 재시도하지 않고 한 번에 DLQ로 보낸다")
    void rejectIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        RetryTemplate template = listenerTemplate();
        assertThatThrownBy(() -> template.execute(c -> {
            calls.incrementAndGet();
            throw new IllegalStateException("감쌈", new AmqpRejectAndDontRequeueException("형식 오류"));
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    // [상황] 형식 오류가 아닌 실패(예: 잠깐 DB 끊김)가 난다.
    // [기대] 최대 횟수(3번)까지 다시 해 본다.
    @Test
    @DisplayName("그 밖의 실패는 max-attempts(3)까지 다시 해 본다")
    void otherFailuresAreRetried() {
        AtomicInteger calls = new AtomicInteger();
        RetryTemplate template = listenerTemplate();
        assertThatThrownBy(() -> template.execute(c -> {
            calls.incrementAndGet();
            throw new IllegalStateException("잠깐 DB 끊김");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(3);
    }
}
