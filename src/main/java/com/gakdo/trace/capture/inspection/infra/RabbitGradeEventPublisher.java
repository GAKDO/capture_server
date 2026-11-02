package com.gakdo.trace.capture.inspection.infra;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.gakdo.trace.capture.captureset.infra.AmqpConfig;
import com.gakdo.trace.capture.inspection.application.CheckpointGradedEvent;
import com.gakdo.trace.capture.inspection.application.GradeEventPublisher;

/** 메시지 ID = eventId. 채널 트랜잭션이라 등급 저장 트랜잭션이 커밋될 때 함께 나간다(CA1과 같음). */
@Component
public class RabbitGradeEventPublisher implements GradeEventPublisher {

    private final RabbitTemplate rabbit;

    public RabbitGradeEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publishGraded(CheckpointGradedEvent event) {
        rabbit.convertAndSend(AmqpConfig.EXCHANGE, CheckpointGradedEvent.ROUTING_KEY, event, message -> {
            message.getMessageProperties().setMessageId(event.eventId());
            return message;
        });
    }
}
