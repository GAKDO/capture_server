package com.gakdo.trace.capture.inspection.domain;

/** 흠집 아님(DISMISSED) 사유. codes.yaml inspection.dismiss_reason 값(11/8): 먼지·스티커·반사·부품·기타. 재학습 음성 예시로 쓴다. */
public enum DismissReason {
    DUST, TAPE, REFLECTION, PART, OTHER
}
