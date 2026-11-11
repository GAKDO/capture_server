package com.gakdo.trace.capture.purge.infra;

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
import com.gakdo.trace.capture.captureset.application.PhotoStorage;
import com.gakdo.trace.capture.purge.application.CheckpointPurgeService;
import com.gakdo.trace.capture.purge.application.CheckpointPurgedEvent;
import com.gakdo.trace.capture.purge.application.PurgeStore;

/**
 * trace.checkpoint.purged 소비. 시리얼이 TEST-가 아니거나 형식이 깨진 메시지는 지우지 않고 DLX로 보낸다.
 * 행은 서비스 트랜잭션으로 지우고, 커밋 뒤 MinIO 객체를 지운다(객체 삭제 실패는 로그만 남김).
 */
@Component
public class PurgeEventListener {

    private static final Logger log = LoggerFactory.getLogger(PurgeEventListener.class);

    private final CheckpointPurgeService service;
    private final PhotoStorage storage;
    private final ObjectReader reader;

    public PurgeEventListener(CheckpointPurgeService service, PhotoStorage storage, ObjectMapper objectMapper) {
        this.service = service;
        this.storage = storage;
        this.reader = objectMapper.readerFor(CheckpointPurgedEvent.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @RabbitListener(queues = PurgeAmqpConfig.QUEUE)
    public void onMessage(Message message) {
        handle(message.getBody());
    }

    public void handle(byte[] body) {
        CheckpointPurgedEvent event;
        try {
            event = reader.readValue(body);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("시험 시점 지움 이벤트 JSON을 읽을 수 없어 DLX로 보냅니다: " + e.getMessage(), e);
        }
        if (!event.isValidTestPurge()) {
            throw new AmqpRejectAndDontRequeueException("시험 데이터가 아니거나 형식이 맞지 않아 지우지 않고 DLX로 보냅니다 eventId="
                    + event.eventId());
        }
        PurgeStore.Keys keys;
        try {
            keys = service.purge(event);
        } catch (IllegalArgumentException e) {
            throw new AmqpRejectAndDontRequeueException("시험 시점을 지우지 않고 DLX로 보냅니다: " + e.getMessage(), e);
        }
        int failed = 0;
        for (String key : keys.captureKeys()) {
            failed += deleteQuietly(key, () -> storage.deleteCapture(key));
        }
        for (String key : keys.derivedKeys()) {
            failed += deleteQuietly(key, () -> storage.deleteDerived(key));
        }
        if (failed > 0) {
            log.warn("시험 시점 객체 일부를 지우지 못함 checkpoint={} 실패={}", event.checkpointId(), failed);
        }
    }

    private static int deleteQuietly(String key, Runnable delete) {
        try {
            delete.run();
            return 0;
        } catch (RuntimeException e) {
            log.warn("시험 시점 객체를 지우지 못함 key={}", key, e);
            return 1;
        }
    }
}
