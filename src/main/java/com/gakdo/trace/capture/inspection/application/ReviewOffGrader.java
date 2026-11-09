package com.gakdo.trace.capture.inspection.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import com.gakdo.trace.capture.captureset.domain.Quality;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.inspection.domain.DefectSource;

/**
 * 검수를 끄고(REVIEW_REQUIRED=false) 다시 띄우면, 검수를 기다리던 세트에 바로 등급을 매긴다.
 * 후보 없는 세트 기다리기(REVIEW_HOLD_EMPTY=false)를 끄고 다시 띄우면 AI 후보 없이 기다리던 세트에도 매긴다(11/9).
 * 등급은 탐지·정합·검수 완료 때만 매기므로, 이미 결과가 다 온 채 검수를 기다리던 세트는 스위치만 바꿔서는 다시 매겨지지 않는다.
 * 그때까지 저장한 결정은 그대로 쓴다(기각한 후보는 빠지고, 정하지 않은 후보는 흠집으로 센다). 세트마다 따로 잠가 하나가 실패해도 나머지는 계속한다.
 */
@Component
public class ReviewOffGrader {

    private static final Logger log = LoggerFactory.getLogger(ReviewOffGrader.class);

    private final CaptureSetRepository sets;
    private final GradingService grading;
    private final TransactionOperations tx;

    public ReviewOffGrader(CaptureSetRepository sets, GradingService grading, TransactionOperations tx) {
        this.sets = sets;
        this.grading = grading;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        gradeStuck();
    }

    /** @return 등급을 매긴 세트 수 */
    public int gradeStuck() {
        EnumSet<SetStatus> open = EnumSet.of(SetStatus.UPLOADED, SetStatus.INSPECTED);
        List<String> ids = new ArrayList<>();
        if (!grading.isReviewRequired()) {
            ids.addAll(sets.findStuckInReview(open, Quality.UNJUDGEABLE, DefectSource.AUTO));
        }
        // 후보 없는 세트 기다리기(REVIEW_HOLD_EMPTY)를 끄면 그동안 기다리던 세트도 바로 등급을 매긴다(11/9).
        // 후보가 남은 세트는 gradeIfReady가 그대로 검수 대기로 둔다
        if (!grading.isHoldEmpty()) {
            sets.findWaitingForGrade(open, Quality.UNJUDGEABLE).stream()
                    .filter(id -> !ids.contains(id)).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            return 0;
        }
        int graded = 0;
        for (String id : ids) {
            try {
                Boolean done = tx.execute(status -> sets.findForUpdate(id).map(grading::gradeIfReady).orElse(false));
                if (Boolean.TRUE.equals(done)) {
                    graded++;
                }
            } catch (RuntimeException e) {
                log.warn("검수 끔: 기다리던 세트 등급 실패 set={}", id, e);
            }
        }
        log.info("검수 끔: 검수를 기다리던 세트 {}개 중 {}개에 등급을 매겼습니다", ids.size(), graded);
        return graded;
    }
}
