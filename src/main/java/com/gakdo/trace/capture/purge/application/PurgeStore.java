package com.gakdo.trace.capture.purge.application;

import java.util.List;

/** 시점 하나에 딸린 촬영·검사 행을 지우는 포트. 구현은 infra(JDBC). */
public interface PurgeStore {

    /** 지울 객체 키: 원본 사진(trace-captures)과 펼친 이미지·텍스처(trace-derived). 행을 지우기 전에 읽는다. */
    Keys keysOf(String checkpointId);

    /** 그 시점의 세트·사진·정합·텍스처·흠집·매칭·등급을 지우고 지운 세트 수를 돌려준다. */
    int deleteCheckpoint(String checkpointId);

    record Keys(List<String> captureKeys, List<String> derivedKeys) {
    }
}
