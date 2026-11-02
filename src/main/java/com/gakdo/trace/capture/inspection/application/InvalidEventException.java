package com.gakdo.trace.capture.inspection.application;

/** 재시도해도 소용없는 이벤트(형식 오류·없는 세트). 리스너가 다시 넣지 않고 DLX로 보낸다. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
