package com.gakdo.trace.capture.inspection.application;

/** trace.checkpoint.graded 발행(포트). 구현은 infra의 RabbitMQ. */
public interface GradeEventPublisher {

    void publishGraded(CheckpointGradedEvent event);
}
