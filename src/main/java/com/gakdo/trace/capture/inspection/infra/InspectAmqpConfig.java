package com.gakdo.trace.capture.inspection.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
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
}
