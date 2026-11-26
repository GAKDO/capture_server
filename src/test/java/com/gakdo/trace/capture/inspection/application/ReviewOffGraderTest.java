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

    // 가짜 촬영 세트 저장소 (테스트마다 새로 만든다)
    private CaptureSetRepository sets;
    // 가짜 등급 서비스
    private GradingService grading;
    // 시험할 대상: 검수를 기다리던 세트에 등급을 매기는 도우미
    private ReviewOffGrader grader;

    // 테스트 도구: 가짜 세트 저장소와 가짜 등급 서비스로 등급 매기기 도우미를 만든다.
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 세트 저장소를 가짜로 만든다
        sets = mock(CaptureSetRepository.class);
        // 준비: 등급 서비스를 가짜로 만든다
        grading = mock(GradingService.class);
        // 준비: 가짜 둘과 "트랜잭션 없이 그냥 실행"하는 도구로 시험 대상을 만든다 (DB 없이 돌리려고)
        grader = new ReviewOffGrader(sets, grading, TransactionOperations.withoutTransaction());
    }

    // [상황] 검수가 켜져 있고, AI 후보가 없는 세트도 검수를 기다리게 되어 있다.
    // [기대] 기다리던 세트를 하나도 건드리지 않는다(0건).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("검수를 켠 채로는 기다리던 세트를 건드리지 않는다")
    void reviewOnDoesNothing() {
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: "검수가 켜져 있나?"에 true로 답하게 한다
        when(grading.isReviewRequired()).thenReturn(true);
        // 준비: "AI 후보 없는 세트도 검수를 기다리나?"에 true로 답하게 한다
        when(grading.isHoldEmpty()).thenReturn(true);

        // (도구) assertThat(값).isZero() = 값이 0이어야 한다는 검사
        // 실행+확인: 멈춘 세트 등급 매기기를 돌리면 매긴 건수가 0이어야 한다
        assertThat(grader.gradeStuck()).isZero();
        // (도구) verify(가짜, never()).메서드(...) = 그 메서드가 한 번도 불리지 않았어야 한다는 검사. any() = 아무 값
        // 확인: 검수 대기 세트를 찾지도 않았어야 한다
        verify(sets, never()).findStuckInReview(any(), any(), any());
        // 확인: 후보 없이 기다리던 세트도 찾지 않았어야 한다
        verify(sets, never()).findWaitingForGrade(any(), any());
    }

    // [상황] 검수는 켜져 있지만 "AI 후보가 없는 세트는 기다리지 않기"로 바꿨다(11/9).
    // [기대] 후보 없이 검수를 기다리던 세트에만 등급을 매긴다(1건). 후보가 있는 세트는 여전히 검수를 기다린다.
    @Test
    @DisplayName("후보 없는 세트 기다리기를 끄면 AI 후보 없이 검수를 기다리던 세트에 등급을 매긴다(11/9)")
    void holdEmptyOffGradesWaitingEmptySets() {
        // 준비: 검수는 켜져 있다고 답하게 한다
        when(grading.isReviewRequired()).thenReturn(true);
        // 준비: AI 후보 없는 세트는 기다리지 않는다(false)고 답하게 한다
        when(grading.isHoldEmpty()).thenReturn(false);
        // 준비: 후보 없는 세트 하나를 가짜로 만든다
        CaptureSet empty = mock(CaptureSet.class);
        // 준비: 후보 없이 기다리던 세트 목록을 물으면 ID "e" 하나를 돌려주게 한다
        when(sets.findWaitingForGrade(any(), any())).thenReturn(List.of("e"));
        // 준비: "e"를 잠가서 읽으면 위 가짜 세트를 돌려주게 한다
        when(sets.findForUpdate("e")).thenReturn(Optional.of(empty));
        // 준비: 그 세트에 등급을 매기면 "매겼다(true)"고 답하게 한다
        when(grading.gradeIfReady(empty)).thenReturn(true);

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 실행+확인: 등급을 매긴 건수가 1이어야 한다
        assertThat(grader.gradeStuck()).isEqualTo(1);
        // 확인: 검수는 켜져 있으니 후보 있는 검수 대기 세트는 찾지 않았어야 한다
        verify(sets, never()).findStuckInReview(any(), any(), any());
    }

    // [상황] 검수를 껐고, 기다리던 세트 4개 중 하나는 등급 매기다 오류, 하나는 정상, 하나는 아직 준비 안 됨, 하나는 이미 사라졌다.
    // [기대] 세트마다 잠그고 등급을 매기며, 하나가 실패해도 나머지는 계속한다. 실제로 등급이 난 것은 1건이다.
    @Test
    @DisplayName("검수를 끄면 기다리던 세트마다 잠가서 등급을 매기고, 하나가 실패해도 나머지는 매긴다")
    void reviewOffGradesEachStuckSet() {
        // 준비: 검수가 꺼져 있다고 답하게 한다
        when(grading.isReviewRequired()).thenReturn(false);
        // 준비: 등급 매기다 오류가 날 세트를 가짜로 만든다
        CaptureSet broken = mock(CaptureSet.class);
        // 준비: 정상으로 등급이 날 세트를 가짜로 만든다
        CaptureSet ok = mock(CaptureSet.class);
        // 준비: 아직 준비가 안 된 세트를 가짜로 만든다
        CaptureSet notReady = mock(CaptureSet.class);
        // 준비: 검수 대기 세트 목록을 물으면 a·b·c·gone 네 개를 돌려주게 한다
        when(sets.findStuckInReview(any(), any(), any())).thenReturn(List.of("a", "b", "c", "gone"));
        // 준비: a를 잠가 읽으면 오류 날 세트를 돌려주게 한다
        when(sets.findForUpdate("a")).thenReturn(Optional.of(broken));
        // 준비: b는 정상 세트
        when(sets.findForUpdate("b")).thenReturn(Optional.of(ok));
        // 준비: c는 준비 안 된 세트
        when(sets.findForUpdate("c")).thenReturn(Optional.of(notReady));
        // 준비: gone은 그사이 사라져 빈 값을 돌려주게 한다
        when(sets.findForUpdate("gone")).thenReturn(Optional.empty());
        // 준비: 오류 날 세트에 등급을 매기면 예외를 던지게 한다 (thenThrow = 결과 대신 예외)
        when(grading.gradeIfReady(broken)).thenThrow(new IllegalStateException("깨진 세트"));
        // 준비: 정상 세트는 "매겼다(true)"
        when(grading.gradeIfReady(ok)).thenReturn(true);
        // 준비: 준비 안 된 세트는 "안 매겼다(false)"
        when(grading.gradeIfReady(notReady)).thenReturn(false);

        // 실행+확인: 실제로 등급이 난 건수는 정상 세트 1건이어야 한다 (오류가 나도 멈추지 않음)
        assertThat(grader.gradeStuck()).isEqualTo(1);
        // (도구) verify(가짜).메서드(값) = 그 메서드가 이 값으로 불렸어야 한다는 검사
        // 확인: a에서 오류가 났어도 b(정상 세트)까지 이어서 시도했어야 한다
        verify(grading).gradeIfReady(ok);
        // 확인: c(준비 안 된 세트)도 시도했어야 한다
        verify(grading).gradeIfReady(notReady);
    }
}
