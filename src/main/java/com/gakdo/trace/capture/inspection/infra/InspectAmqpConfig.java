package com.gakdo.trace.capture.inspection.infra;

import java.util.Map;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gakdo.trace.capture.captureset.infra.AmqpConfig;
import com.gakdo.trace.capture.inspection.application.InspectEvent;

/**
 * capture-server의 소비 큐 trace.capture.inspected(contracts x-consumers)를 trace.events에 붙인다.
 * 계속 실패한 메시지는 trace.dlx(topic)로 보내고 dead-letter 키 trace.capture.inspected.dead로 우리 DLQ에 모은다(RE1과 같은 규칙).
 */
@Configuration
public class InspectAmqpConfig {

    public static final String QUEUE = "trace.capture.inspected";
    public static final String DLX = "trace.dlx";
    public static final String DEAD_KEY = "trace.capture.inspected.dead";
    public static final String DLQ = "trace.capture.inspected.dlq";

    @Bean
    Declarables inspectTopology() {
        TopicExchange events = new TopicExchange(AmqpConfig.EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_KEY).build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        Binding inspected = BindingBuilder.bind(queue).to(events).with(InspectEvent.ROUTING_KEY);
        Binding dead = BindingBuilder.bind(dlq).to(dlx).with(DEAD_KEY);
        return new Declarables(dlx, queue, dlq, inspected, dead);
    }

    /**
     * 리스너 재시도(max-attempts)에서 '다시 해도 안 되는' 실패는 뺀다. 형식 오류로 던진
     * AmqpRejectAndDontRequeueException(원인 사슬 포함)은 바로 DLQ로 가고, 그 밖의 예외만 재시도한다.
     */
    @Bean
    RabbitRetryTemplateCustomizer noRetryOnReject(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxAttempts) {
        return (target, template) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                template.setRetryPolicy(new SimpleRetryPolicy(maxAttempts,
                        Map.of(AmqpRejectAndDontRequeueException.class, false), true, true));
            }
        };
    }
}
