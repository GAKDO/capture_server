package com.gakdo.trace.capture.captureset.domain;

/** 조명. codes.yaml capture.light. 부스 L1~L4(+SCR1의 OFF), 폰 ROOM(+OFF) */
public enum Light {
    /** 부스 조명 1: 확산광(고르게 퍼진 빛) */
    L1,
    /** 부스 조명 2: 왼쪽에서 비추는 빛 */
    L2,
    /** 부스 조명 3: 뒤에서 비추는 빛 */
    L3,
    /** 부스 조명 4: 앞에서 낮게 스치는 빛(흠집 그림자가 잘 보임) */
    L4,
    /** 폰 촬영: 그 방의 조명 그대로 */
    ROOM,
    /** 조명 끔(흰 화면 SCR1처럼 화면 빛만으로 찍을 때) */
    OFF
}
