package com.gakdo.trace.capture.captureset.infra;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.gakdo.trace.capture.captureset.application.CaptureEventPublisher;
import com.gakdo.trace.capture.captureset.application.CaptureUploadedEvent;

/** 메시지 ID = eventId(소비자가 멱등 처리에 쓴다). 영속 메시지(Spring AMQP 기본). */
@Component
public class RabbitCaptureEventPublisher implements CaptureEventPublisher {

    private final RabbitTemplate rabbit;

    public RabbitCaptureEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publishUploaded(CaptureUploadedEvent event) {
        rabbit.convertAndSend(AmqpConfig.EXCHANGE, CaptureUploadedEvent.ROUTING_KEY, event, message -> {
            message.getMessageProperties().setMessageId(event.eventId());
            return message;
        });
    }
}
