package com.gakdo.trace.capture.captureset.domain;

/**
 * 찍은 기기의 기종과 3D 트윈 치수(mm). contracts CaptureDevice·이벤트 $defs/DeviceModel과 같은 모양이다.
 * 값은 클라이언트가 rental-server 기종 정보를 옮긴 것이고, capture-server는 저장했다가 이벤트로 넘기기만 한다.
 */
public record DeviceModel(String modelCode, Dimensions dimensions) {

    public record Dimensions(double widthMm, double depthMm, double baseHMm, double lidHMm) {
    }
}
