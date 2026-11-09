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

    @BeforeEach
    void setUp() {
        sets = mock(CaptureSetRepository.class);
        grading = mock(GradingService.class);
        grader = new ReviewOffGrader(sets, grading, TransactionOperations.withoutTransaction());
    }

    @Test
    @DisplayName("검수를 켠 채로는 기다리던 세트를 건드리지 않는다")
    void reviewOnDoesNothing() {
        when(grading.isReviewRequired()).thenReturn(true);
        when(grading.isHoldEmpty()).thenReturn(true);

        assertThat(grader.gradeStuck()).isZero();
        verify(sets, never()).findStuckInReview(any(), any(), any());
        verify(sets, never()).findWaitingForGrade(any(), any());
    }

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
