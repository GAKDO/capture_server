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

    private RetryTemplate listenerTemplate() {
        RetryTemplate template = new RetryTemplate();
        new InspectAmqpConfig().noRetryOnReject(3).customize(Target.LISTENER, template);
        return template;
    }

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
