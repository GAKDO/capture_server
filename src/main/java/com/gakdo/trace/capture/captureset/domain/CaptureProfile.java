package com.gakdo.trace.capture.captureset.domain;

/**
 * 세트 범위. codes.yaml capture.profile (11/3 MVP 재조정).
 * FULL은 출처별 face_required, QUICK은 부스 빠른 검사(face_required_quick, 위·아래 2면)로 정합 없이 부스 탐지만으로 등급을 매긴다.
 */
public enum CaptureProfile {
    FULL, QUICK
}
