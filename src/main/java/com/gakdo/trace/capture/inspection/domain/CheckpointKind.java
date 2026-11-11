package com.gakdo.trace.capture.inspection.domain;

/**
 * 시점 종류. codes.yaml rental.checkpoint_type 값(OUT 출고·RCV 수령·RET 반납).
 * 세트 생성 계약에 시점 종류가 없고 서버끼리 REST를 부르지 않으므로, 같은 렌탈에서 이미 등급이 난 다른 시점 수로 정한다.
 * rental-server가 렌탈마다 시점 종류를 하나씩만 두고(UNIQUE) 앞 시점이 봉인돼야 다음 시점을 열게 하므로, 앞서 등급 난 시점이
 * 0개면 출고, 1개면 수령, 2개 이상이면 반납이다. 촬영 출처(부스·폰)와 무관해 부스 빠른 검사로도 수령을 찍을 수 있다(11/4).
 */
public enum CheckpointKind {
    OUT, RCV, RET;

    /** @param earlierGraded 같은 렌탈에서 등급이 난 다른 시점 수 */
    public static CheckpointKind ofPosition(long earlierGraded) {
        if (earlierGraded <= 0) {
            return OUT;
        }
        return earlierGraded == 1 ? RCV : RET;
    }
}
