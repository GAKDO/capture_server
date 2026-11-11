package com.gakdo.trace.capture.comparison.domain;

/** 흠집 종류. codes.yaml inspection.defect_class 값(scratch·dent·crack·stain)을 code()로 돌려준다. */
public enum DefectClass {
    SCRATCH, DENT, CRACK, STAIN;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static DefectClass fromCode(String code) {
        return valueOf(code.toUpperCase(java.util.Locale.ROOT));
    }
}
