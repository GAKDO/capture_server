package com.gakdo.trace.capture.comparison.domain;

import java.util.List;
import java.util.Set;

import com.gakdo.trace.capture.captureset.domain.Face;

/**
 * 한 시점의 흠집과 판정할 수 있는 면. 판정 불가 면(품질 미달·건너뜀)은 judgeableFaces에서 빼서 넘긴다.
 * 어떤 면이 판정 불가인지는 부르는 쪽(결과 저장·등급, A)이 정한다.
 */
public record ViewSnapshot(List<DefectObservation> defects, Set<Face> judgeableFaces) {

    public ViewSnapshot {
        defects = List.copyOf(defects);
        judgeableFaces = Set.copyOf(judgeableFaces);
    }
}
