package com.gakdo.trace.capture.captureset.domain;

/** 세트 상태. codes.yaml capture.set_status. CA1은 UPLOADING → UPLOADED까지 */
public enum SetStatus {
    UPLOADING, UPLOADED, INSPECTED, GRADED
}
