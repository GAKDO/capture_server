package com.gakdo.trace.capture.captureset.application;

/** trace.events로 이벤트를 낸다. 호출한 트랜잭션이 커밋될 때 함께 나간다(구현: 채널 트랜잭션). */
public interface CaptureEventPublisher {

    void publishUploaded(CaptureUploadedEvent event);
}
