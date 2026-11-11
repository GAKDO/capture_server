package com.gakdo.trace.capture.captureset.application;

/**
 * 빠른 검사(QUICK) 세트가 완료됐다는 서버 안 알림(11/3 MVP). 완료 트랜잭션 안에서 동기로 전달되고, inspection이 받아
 * 부스 탐지가 이미 와 있으면 정합 없이 바로 등급을 매긴다. RabbitMQ로 나가는 이벤트가 아니다.
 */
public record QuickSetCompleted(String captureSetId) {
}
