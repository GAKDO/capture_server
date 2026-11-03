package com.gakdo.trace.capture.inspection.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.application.QuickSetCompleted;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;

/**
 * 빠른 검사 세트 완료를 받아, 부스 탐지가 먼저 와 있었으면 같은 트랜잭션에서 바로 등급을 매긴다(11/3 MVP).
 * 탐지가 아직이면 아무것도 하지 않고, 탐지 저장(DetectionService)이 등급을 매긴다.
 */
@Component
public class QuickGradeListener {

    private final CaptureSetRepository sets;
    private final GradingService grading;

    public QuickGradeListener(CaptureSetRepository sets, GradingService grading) {
        this.sets = sets;
        this.grading = grading;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(QuickSetCompleted event) {
        sets.findByPublicId(event.captureSetId()).ifPresent(grading::gradeIfReady);
    }
}
