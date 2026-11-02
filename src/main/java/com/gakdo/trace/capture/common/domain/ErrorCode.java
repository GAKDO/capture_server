package com.gakdo.trace.capture.common.domain;

import org.springframework.http.HttpStatus;

/**
 * 이 서버가 내는 오류 code. 값은 contracts codes.yaml {@code error_code}와 같아야 한다(복사가 아니라 이름 대조, ErrorCodeTest).
 * 제목은 화면이 그대로 보여 줄 수 있는 한국어 짧은 문장이다.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "이 시점의 촬영 권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "같은 요청 키로 다른 요청을 보냈습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
    CAPTURE_FACES_MISSING(HttpStatus.CONFLICT, "촬영하지 않은 면이 있습니다"),
    CAPTURE_HASH_MISMATCH(HttpStatus.CONFLICT, "올린 사진이 처음 알린 사진과 다릅니다"),
    CAPTURE_SET_CLOSED(HttpStatus.CONFLICT, "이미 완료된 촬영 세트입니다");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
