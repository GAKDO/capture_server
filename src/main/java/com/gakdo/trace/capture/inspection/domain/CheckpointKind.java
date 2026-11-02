package com.gakdo.trace.capture.inspection.domain;

import com.gakdo.trace.capture.captureset.domain.CaptureSource;

/**
 * 시점 종류. codes.yaml rental.checkpoint_type 값(OUT 출고·RCV 수령·RET 반납).
 * 세트 생성 계약에 시점 종류가 없고 서버끼리 REST를 부르지 않으므로 출처와 앞 시점으로 정한다(specs/003 "정한 것").
 * rental-server가 시점 순서(앞 시점이 봉인돼야 다음 시점)를 지키므로: 폰 = 수령, 부스는 앞 시점이 있으면 반납, 없으면 출고.
 */
public enum CheckpointKind {
    OUT, RCV, RET;

    public static CheckpointKind infer(CaptureSource source, boolean hasPrevious) {
        if (source == CaptureSource.PHONE) {
            return RCV;
        }
        return hasPrevious ? RET : OUT;
    }
}
