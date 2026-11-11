package com.gakdo.trace.capture.inspection.infra;

import java.io.IOException;

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
            // 재시도하지 않고 DLX로 보낸다. 로그는 리스너 컨테이너가 이 예외와 함께 남긴다
            throw new AmqpRejectAndDontRequeueException("검사 완료 이벤트 JSON을 읽을 수 없어 DLX로 보냅니다: " + e.getMessage(), e);
        }
        try {
            return service.onInspected(event);
        } catch (InvalidEventException e) {
            throw new AmqpRejectAndDontRequeueException("검사 완료 이벤트가 올바르지 않아 DLX로 보냅니다 eventId="
                    + event.eventId() + " set=" + event.captureSetId() + ": " + e.getMessage(), e);
        }
    }
}
