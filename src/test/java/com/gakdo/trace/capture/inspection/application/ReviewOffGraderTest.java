// 이 파일이 확인하는 것: 사람 검수(11/8)를 끄고 서버를 다시 띄웠을 때(REVIEW_REQUIRED=false), 검수를 기다리며 멈춰 있던 세트에 등급을 매기는 일.
// 검수를 다시 끄는 것은 비상용 되돌리기이므로, 기다리던 세트가 영영 등급 없이 남지 않게 한다.

package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;

/** 검수를 끄고 다시 띄울 때(REVIEW_REQUIRED=false) 검수를 기다리던 세트에 등급을 매긴다. Docker 없이 돈다. */
class ReviewOffGraderTest {

    private CaptureSetRepository sets;
    private GradingService grading;
    private ReviewOffGrader grader;

    // 테스트 도구: 가짜 세트 저장소와 가짜 등급 서비스로 등급 매기기 도우미를 만든다.
    @BeforeEach
    void setUp() {
        sets = mock(CaptureSetRepository.class);
        grading = mock(GradingService.class);
        grader = new ReviewOffGrader(sets, grading, TransactionOperations.withoutTransaction());
    }

    // [상황] 검수가 켜져 있고, AI 후보가 없는 세트도 검수를 기다리게 되어 있다.
    // [기대] 기다리던 세트를 하나도 건드리지 않는다(0건).
    @Test
    @DisplayName("검수를 켠 채로는 기다리던 세트를 건드리지 않는다")
    void reviewOnDoesNothing() {
        when(grading.isReviewRequired()).thenReturn(true);
        when(grading.isHoldEmpty()).thenReturn(true);

        assertThat(grader.gradeStuck()).isZero();
        verify(sets, never()).findStuckInReview(any(), any(), any());
        verify(sets, never()).findWaitingForGrade(any(), any());
    }

    // [상황] 검수는 켜져 있지만 "AI 후보가 없는 세트는 기다리지 않기"로 바꿨다(11/9).
    // [기대] 후보 없이 검수를 기다리던 세트에만 등급을 매긴다(1건). 후보가 있는 세트는 여전히 검수를 기다린다.
    @Test
    @DisplayName("후보 없는 세트 기다리기를 끄면 AI 후보 없이 검수를 기다리던 세트에 등급을 매긴다(11/9)")
    void holdEmptyOffGradesWaitingEmptySets() {
        when(grading.isReviewRequired()).thenReturn(true);
        when(grading.isHoldEmpty()).thenReturn(false);
        CaptureSet empty = mock(CaptureSet.class);
        when(sets.findWaitingForGrade(any(), any())).thenReturn(List.of("e"));
        when(sets.findForUpdate("e")).thenReturn(Optional.of(empty));
        when(grading.gradeIfReady(empty)).thenReturn(true);

        assertThat(grader.gradeStuck()).isEqualTo(1);
        verify(sets, never()).findStuckInReview(any(), any(), any());
    }

    // [상황] 검수를 껐고, 기다리던 세트 4개 중 하나는 등급 매기다 오류, 하나는 정상, 하나는 아직 준비 안 됨, 하나는 이미 사라졌다.
    // [기대] 세트마다 잠그고 등급을 매기며, 하나가 실패해도 나머지는 계속한다. 실제로 등급이 난 것은 1건이다.
    @Test
    @DisplayName("검수를 끄면 기다리던 세트마다 잠가서 등급을 매기고, 하나가 실패해도 나머지는 매긴다")
    void reviewOffGradesEachStuckSet() {
        when(grading.isReviewRequired()).thenReturn(false);
        CaptureSet broken = mock(CaptureSet.class);
        CaptureSet ok = mock(CaptureSet.class);
        CaptureSet notReady = mock(CaptureSet.class);
        when(sets.findStuckInReview(any(), any(), any())).thenReturn(List.of("a", "b", "c", "gone"));
        when(sets.findForUpdate("a")).thenReturn(Optional.of(broken));
        when(sets.findForUpdate("b")).thenReturn(Optional.of(ok));
        when(sets.findForUpdate("c")).thenReturn(Optional.of(notReady));
        when(sets.findForUpdate("gone")).thenReturn(Optional.empty());
        when(grading.gradeIfReady(broken)).thenThrow(new IllegalStateException("깨진 세트"));
        when(grading.gradeIfReady(ok)).thenReturn(true);
        when(grading.gradeIfReady(notReady)).thenReturn(false);

        assertThat(grader.gradeStuck()).isEqualTo(1);
        verify(grading).gradeIfReady(ok);
        verify(grading).gradeIfReady(notReady);
    }
}
