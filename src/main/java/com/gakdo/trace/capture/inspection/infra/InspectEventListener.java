package com.gakdo.trace.capture.inspection.infra;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.gakdo.trace.capture.inspection.application.InspectEvent;
import com.gakdo.trace.capture.inspection.application.InspectionService;
import com.gakdo.trace.capture.inspection.application.InvalidEventException;

/**
 * trace.inspect.completed 소비. 모르는 필드는 무시하고(부 버전이 올라도 읽힘), 형식이 깨진 메시지는 재시도해도 소용없어 바로 DLX로 보낸다.
 * DB 오류 같은 일시 오류는 재시도(spring.rabbitmq.listener.simple.retry) 뒤 DLX.
 */
@Component
public class InspectEventListener {

    private static final Logger log = LoggerFactory.getLogger(InspectEventListener.class);

    private final InspectionService service;
    private final ObjectReader reader;

    public InspectEventListener(InspectionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.reader = objectMapper.readerFor(InspectEvent.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @RabbitListener(queues = InspectAmqpConfig.QUEUE)
    public void onMessage(Message message) {
        handle(message.getBody());
    }

    public boolean handle(byte[] body) {
        InspectEvent event;
        try {
            event = reader.readValue(body);
        } catch (IOException e) {
            log.warn("검사 완료 이벤트를 읽을 수 없어 DLX로 보냅니다: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException("이벤트 JSON을 읽을 수 없습니다", e);
        }
        try {
            return service.onInspected(event);
        } catch (InvalidEventException e) {
            log.warn("검사 완료 이벤트가 올바르지 않아 DLX로 보냅니다 eventId={} set={}: {}", event.eventId(),
                    event.captureSetId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
    }
}
