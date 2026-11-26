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
        // 빈 재시도 틀을 만든다 (RetryTemplate = 실패하면 정해진 규칙대로 다시 실행해 주는 도구)
        RetryTemplate template = new RetryTemplate();
        // 운영 코드의 설정(최대 3번, 형식 오류 거절은 재시도 안 함)을 리스너용으로 그 틀에 적용한다
        new InspectAmqpConfig().noRetryOnReject(3).customize(Target.LISTENER, template);
        // 설정한 틀을 돌려준다
        return template;
    }

    // [상황] 메시지 형식 오류로 거절했다(그 거절이 다른 예외 안에 감싸여 있어도).
    // [기대] 다시 시도하지 않고 딱 한 번만 처리한 뒤 실패함(DLQ)으로 보낸다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("형식 오류로 거절한 메시지(원인 사슬 안에 있어도)는 재시도하지 않고 한 번에 DLQ로 보낸다")
    void rejectIsNotRetried() {
        // 준비: 몇 번 실행됐는지 셀 계수기를 만든다
        AtomicInteger calls = new AtomicInteger();
        // 준비: 운영과 같은 재시도 틀을 만든다
        RetryTemplate template = listenerTemplate();
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행: 재시도 틀 안에서 아래 일을 실행한다
        assertThatThrownBy(() -> template.execute(c -> {
            // 실행 횟수를 1 올린다
            calls.incrementAndGet();
            // "형식 오류로 거절" 예외를 다른 예외로 감싸 던진다 (원인 사슬 안에 숨어 있는 경우)
            throw new IllegalStateException("감쌈", new AmqpRejectAndDontRequeueException("형식 오류"));
        // 확인: 결국 감싼 예외(IllegalStateException)가 밖으로 나와야 한다
        })).isInstanceOf(IllegalStateException.class);
        // (도구) assertThat(계수기).hasValue(n) = 계수기 값이 n이어야 한다는 검사
        // 확인: 딱 1번만 실행됐어야 한다 (재시도 안 함 → 바로 DLQ)
        assertThat(calls).hasValue(1);
    }

    // [상황] 형식 오류가 아닌 실패(예: 잠깐 DB 끊김)가 난다.
    // [기대] 최대 횟수(3번)까지 다시 해 본다.
    @Test
    @DisplayName("그 밖의 실패는 max-attempts(3)까지 다시 해 본다")
    void otherFailuresAreRetried() {
        // 준비: 실행 횟수 계수기를 만든다
        AtomicInteger calls = new AtomicInteger();
        // 준비: 운영과 같은 재시도 틀을 만든다
        RetryTemplate template = listenerTemplate();
        // 실행: 재시도 틀 안에서 아래 일을 실행한다
        assertThatThrownBy(() -> template.execute(c -> {
            // 실행 횟수를 1 올린다
            calls.incrementAndGet();
            // 일시적 실패를 흉내 낸 예외를 던진다
            throw new IllegalStateException("잠깐 DB 끊김");
        // 확인: 끝내 실패하면 그 예외가 밖으로 나와야 한다
        })).isInstanceOf(IllegalStateException.class);
        // 확인: 최대 횟수인 3번 실행됐어야 한다 (일시적 실패는 다시 해 봄)
        assertThat(calls).hasValue(3);
    }
}
