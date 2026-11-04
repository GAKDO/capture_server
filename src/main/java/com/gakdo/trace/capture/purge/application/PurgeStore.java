package com.gakdo.trace.capture.purge.application;

import java.util.List;

/** 시점 하나에 딸린 촬영·검사 행을 지우는 포트. 구현은 infra(JDBC). */
public interface PurgeStore {

    /** 이 시점의 세트 중 다른 렌탈에 속한 것이 있는지(이벤트의 rentalId·checkpointId 짝이 저장된 값과 다르면 지우지 않는다). */
    boolean belongsToOtherRental(String rentalId, String checkpointId);

    /** 지울 객체 키: 원본 사진(trace-captures)과 펼친 이미지·텍스처(trace-derived). 행을 지우기 전에 읽는다. */
    Keys keysOf(String checkpointId);

    /** 그 시점의 세트·사진·정합·텍스처·흠집·매칭·등급을 지우고 지운 세트 수를 돌려준다. */
    int deleteCheckpoint(String checkpointId);

    record Keys(List<String> captureKeys, List<String> derivedKeys) {
    }
}
