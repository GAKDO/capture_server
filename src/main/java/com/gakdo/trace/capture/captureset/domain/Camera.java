package com.gakdo.trace.capture.captureset.domain;

/** 카메라. codes.yaml capture.camera. 부스 위 카메라 CAM1·CAM2(둘 다 면 전체를 찍음, 11/9 보정), 수령 폰 PHONE */
public enum Camera {
    /** 부스 위 카메라 1 */
    CAM1,
    /** 부스 위 카메라 2 */
    CAM2,
    /** 고객이 수령할 때 쓰는 휴대폰 카메라(웹 촬영 화면) */
    PHONE
}
