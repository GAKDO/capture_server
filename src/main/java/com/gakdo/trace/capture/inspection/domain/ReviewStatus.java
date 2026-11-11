package com.gakdo.trace.capture.inspection.domain;

/** 사람 검수 상태. codes.yaml inspection.review_status 값(11/8). AI 후보는 PENDING으로 시작한다. */
public enum ReviewStatus {
    PENDING, CONFIRMED, DISMISSED
}
