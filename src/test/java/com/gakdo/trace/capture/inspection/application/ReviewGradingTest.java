// 이 파일이 확인하는 것: 사람 검수(11/8)와 등급의 관계. AI는 흠집 후보를 넓게 표시하고, 관리자가 "흠집 맞음(확정)/아님(기각)"을 정한다.
// AI 후보가 있으면 검수 완료 뒤에만 등급을 내고, 기각한 후보는 등급에서 뺀다.
// 검수 완료는 한 번만(같은 Idempotency-Key 재전송은 괜찮음) 할 수 있고, 관리자만 할 수 있다.
// (이 파일은 "후보 없는 세트도 기다리기"를 끈 설정이다. 그 설정을 켠 경우는 HumanDefectTest에서 본다.)

package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.ReviewService.Decision;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DismissReason;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;
import com.gakdo.trace.capture.inspection.infra.ReviewQueueRepository;

/** 사람 검수(11/8): 후보가 있으면 검수 완료 뒤 등급, 기각은 등급에서 뺌. 저장소는 가짜라 Docker 없이 돈다. */
class ReviewGradingTest {

    // 가짜 사진 해시값(64자리 "c")
    private static final String SHA = "c".repeat(64);
    // 검수하는 관리자 ID
    private static final String ADMIN_ID = "admin00000000000000001";
    // 흠집 후보 모양: (10,10)~(50,30) 네모
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    // 요청한 사람: 관리자
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    // 가짜 흠집 저장소
    private DefectRepository defects;
    // 가짜 등급 결과 저장소
    private GradeResultRepository grades;
    // 가짜 등급 이벤트 발행기
    private GradeEventPublisher events;
    // 가짜 세트 저장소
    private CaptureSetRepository sets;
    // 등급 서비스 (진짜)
    private GradingService grading;
    // 시험할 대상: 검수 서비스
    private ReviewService reviews;

    // 테스트 도구: 검수 켬(후보 없는 세트는 기다리지 않음) 설정으로 등급·검수 서비스를 가짜 저장소와 함께 만든다.
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 흠집 저장소를 가짜로 만든다
        defects = mock(DefectRepository.class);
        // 준비: 등급 결과 저장소를 가짜로 만든다
        grades = mock(GradeResultRepository.class);
        // 준비: 이벤트 발행기를 가짜로 만든다
        events = mock(GradeEventPublisher.class);
        // 준비: 세트 저장소를 가짜로 만든다
        sets = mock(CaptureSetRepository.class);
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. anyString() = 아무 글자
        // 준비: 어떤 시점의 기존 등급을 물어도 "없음"으로 답하게 한다
        when(grades.findByCheckpointId(anyString())).thenReturn(Optional.empty());
        // 준비: 같은 렌탈의 앞 시점 등급도 "없음"으로 답하게 한다
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        // 준비: 검수 켬(true) 설정으로 등급 서비스를 만든다 (후보 없는 세트는 기다리지 않음)
        grading = new GradingService(defects, mock(DefectMatchRepository.class), mock(FaceAlignmentRepository.class),
                grades, events, true);
        // 준비: 위 가짜들과 등급 서비스로 검수 서비스를 만든다
        reviews = new ReviewService(sets, defects, mock(ReviewQueueRepository.class), grading,
                mock(InspectionQueryService.class), mock(FaceAlignmentRepository.class));
    }

    // [상황] 윗면에 AI 긁힘 후보가 있는 빠른 검사 세트에 탐지 결과가 왔다.
    // [기대] 검수 완료 전까지 등급을 매기지 않는다(상태 UPLOADED 그대로, 이벤트 없음).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("AI 후보가 있는 세트는 탐지가 와도 검수 완료 전까지 등급을 매기지 않는다")
    void holdsUntilReview() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 이 세트에 윗면 AI 긁힘 후보 하나가 있다고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));

        // (도구) assertThat(값).isFalse() / isTrue() = 값이 거짓 / 참이어야 한다는 검사
        // 실행+확인: 등급을 매기면 "안 매김(false)"이어야 한다
        assertThat(grading.gradeIfReady(set)).isFalse();

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 세트는 올림 완료(UPLOADED) 그대로여야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        // (도구) verify(가짜, never()).메서드(...) = 그 메서드가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 등급 이벤트가 나가지 않았어야 한다
        verify(events, never()).publishGraded(any());
    }

    // [상황] AI 후보가 하나도 없는 세트에 탐지 결과가 왔다.
    // [기대] 검수 없이 바로 등급 A가 나고, 이벤트(스키마 1.3)에 검수 기록(review)은 없다.
    @Test
    @DisplayName("후보가 하나도 없는 세트는 검수 없이 바로 등급이 나고 이벤트에 review가 없다")
    void noCandidatesGradesImmediately() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 이 세트의 후보는 0개라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of());

        // 실행+확인: 등급을 매기면 "매겼다(true)"여야 한다
        assertThat(grading.gradeIfReady(set)).isTrue();

        // 보낸 등급 이벤트를 꺼낸다
        CheckpointGradedEvent sent = sent();
        // 확인: 등급은 A여야 한다
        assertThat(sent.grade()).isEqualTo(Grade.A);
        // (도구) isNull() = 값이 비어 있어야(null) 한다는 검사
        // 확인: 검수 기록(review)은 없어야 한다
        assertThat(sent.review()).isNull();
        // 확인: 이벤트 스키마 버전은 1.3이어야 한다
        assertThat(sent.schemaVersion()).isEqualTo("1.3");
    }

    // [상황] 윗면을 "덮개 고장"으로 건너뛰었는데, 그 면에 탐지가 하나 와 있다.
    // [기대] 건너뛴 면의 탐지는 검수 후보가 아니라서 검수를 기다리지 않고 바로 등급이 난다.
    @Test
    @DisplayName("건너뛴 면의 탐지는 후보가 아니라 검수를 기다리지 않는다")
    void skippedFaceIsNotCandidate() {
        // 준비: 윗면을 "덮개 고장"으로 건너뛴 세트를 만든다
        CaptureSet set = readySet(Map.of(Face.TOP, "덮개 고장"));
        // 준비: 그 윗면에 탐지 하나가 와 있다고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));

        // (도구) isEmpty() = 목록이 비어 있어야 한다는 검사
        // 실행+확인: 검수 후보 목록은 비어 있어야 한다
        assertThat(GradingService.reviewCandidates(set, defects.findBySet(set))).isEmpty();
        // 실행+확인: 검수를 기다리지 않고 바로 등급이 나야 한다
        assertThat(grading.gradeIfReady(set)).isTrue();
    }

    // [상황] 윗면 찍힘 후보는 "먼지"로 기각, 바닥 긁힘 후보는 확정하고 검수를 완료한다.
    // [기대] 결정만 저장했을 때는 등급 이벤트가 없고, 검수 완료 때 찍힘을 뺀 긁힘 하나로 B가 난다(찍힘이 남았으면 C).
    //        이벤트의 검수 기록에 검수한 관리자, 확정 1·기각 1이 실리고 세트는 등급 완료(GRADED)가 된다.
    @Test
    @DisplayName("기각(먼지·스티커)한 후보는 등급에서 빠지고, 확정한 후보만 센다. 이벤트 review에 검수자와 건수가 실린다")
    void dismissedExcludedFromGrade() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 윗면 찍힘 후보 (먼지로 기각할 것)
        Defect dust = dent(set, Face.TOP);
        // 준비: 바닥 긁힘 후보 (확정할 것)
        Defect real = scratch(set, Face.BOT);
        // 준비: 이 세트의 후보는 위 둘이라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(dust, real));
        // 준비: 이 시점의 최신 세트 ID를 물으면 이 세트 ID를 돌려주게 한다
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        // 준비: 그 ID로 잠가 읽으면 이 세트를 돌려주게 한다
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        // 실행: 찍힘은 "먼지(DUST)"로 기각, 긁힘은 확정하는 결정을 저장한다
        reviews.save(ADMIN, ADMIN_ID, set.getCheckpointId(), List.of(
                new Decision(dust.getPublicId(), ReviewStatus.DISMISSED, DismissReason.DUST),
                new Decision(real.getPublicId(), ReviewStatus.CONFIRMED, null)));
        // 확인: 결정만 저장했을 때는 등급 이벤트가 없어야 한다
        verify(events, never()).publishGraded(any());
        // 실행: 검수를 완료한다
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");

        // 보낸 등급 이벤트를 꺼낸다
        CheckpointGradedEvent sent = sent();
        // 확인: 찍힘을 뺀 긁힘 하나로 등급 B여야 한다
        assertThat(sent.grade()).isEqualTo(Grade.B); // 찍힘(C)은 기각돼 긁힘 하나만 남는다
        // (도구) isNotNull() = 값이 비어 있지 않아야 한다는 검사
        // 확인: 검수 기록이 실려야 한다
        assertThat(sent.review()).isNotNull();
        // 확인: 검수한 관리자 ID가 실려야 한다
        assertThat(sent.review().reviewedBy()).isEqualTo(ADMIN_ID);
        // 확인: 확정 1건
        assertThat(sent.review().confirmed()).isEqualTo(1);
        // 확인: 기각 1건
        assertThat(sent.review().dismissed()).isEqualTo(1);
        // 확인: 세트는 등급 완료(GRADED)여야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
        // 확인: 기각한 후보에도 검수자가 남아야 한다
        assertThat(dust.getReviewedBy()).isEqualTo(ADMIN_ID);
    }

    // [상황] 후보 2개를 아직 하나도 정하지 않은 채 검수 완료를 누른다.
    // [기대] 409 REVIEW_PENDING_REMAINS와 남은 수(2)를 알려 주고, 검수 완료 시각은 남기지 않는다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("PENDING이 남아 있으면 검수 완료는 409 REVIEW_PENDING_REMAINS(남은 수 pending)")
    void completeWithPendingIs409() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 아직 정하지 않은 후보 2개가 있다고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP), scratch(set, Face.BOT)));
        // 준비: 이 시점의 최신 세트 ID를 물으면 이 세트 ID를 돌려주게 한다
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        // 준비: 그 ID로 잠가 읽으면 이 세트를 돌려주게 한다
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        // (도구) assertThatThrownBy(() -> 코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 검수 완료를 누르면 예외가 나야 한다
        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001"))
                // (도구) isInstanceOfSatisfying(종류, e -> ...) = 예외가 그 종류이고, 안의 검사도 통과해야 한다는 뜻
                // 확인: 도메인 예외이고 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // 확인: 오류 코드는 "검수 대기 남음"(409)이어야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_PENDING_REMAINS);
                    // (도구) containsEntry(키, 값) = 맵에 그 키가 그 값으로 들어 있어야 한다는 검사
                    // 확인: 남은 수 2가 함께 와야 한다
                    assertThat(e.properties()).containsEntry("pending", 2L);
                });
        // 확인: 검수 완료 시각은 남지 않아야 한다
        assertThat(set.getReviewCompletedAt()).isNull();
    }

    // [상황] 부스 탐지 결과가 오기 전에 검수 완료를 누르고, 탐지가 온 뒤 후보가 없는 상태에서 다시 누른다.
    // [기대] 처음엔 409 REVIEW_NOT_READY(아직 검수할 수 없음), 후보 없는 세트는 이미 바로 등급이 나므로 409 REVIEW_CLOSED다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("부스 탐지가 오기 전에는 검수 완료가 409 REVIEW_NOT_READY, 후보 없는 세트는 409 REVIEW_CLOSED")
    void notReadyOrNoCandidates() {
        // 준비: 다 올렸지만 탐지 결과가 아직 없는 세트를 만든다
        CaptureSet waiting = completedSet(Map.of());
        // 준비: 이 시점의 최신 세트 ID를 물으면 이 세트 ID를 돌려주게 한다
        when(sets.findLatestPublicIdByCheckpointId(waiting.getCheckpointId())).thenReturn(Optional.of(waiting.getPublicId()));
        // 준비: 그 ID로 잠가 읽으면 이 세트를 돌려주게 한다
        when(sets.findForUpdate(waiting.getPublicId())).thenReturn(Optional.of(waiting));
        // 실행+확인: 탐지 전에 검수 완료를 누르면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, waiting.getCheckpointId(), "review-key-0001"))
                // 확인: 오류 코드는 "아직 검수할 수 없음"(409)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_NOT_READY));

        // 준비: 이제 탐지 결과를 받았다고 표시한다
        waiting.markDetectionsReceived();
        // 준비: 후보는 0개라고 답하게 한다
        when(defects.findBySet(waiting)).thenReturn(List.of());
        // 실행+확인: 다시 검수 완료를 누르면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, waiting.getCheckpointId(), "review-key-0001"))
                // 확인: 후보 없는 세트는 검수할 것이 없어 "검수 이미 끝남"(409)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
    }

    // [상황] 빠른 검사 세트가 검수를 기다리는 사이 면 펼치기(정합) 결과가 와서 상태가 INSPECTED로 바뀌었다. 그 뒤 후보를 확정하고 검수를 완료한다.
    // [기대] 그래도 등급 완료(GRADED)가 되고 등급 B가 난다(상태가 바뀌었다고 등급이 막히지 않음).
    @Test
    @DisplayName("검수를 기다리는 사이 정합 결과가 와서 INSPECTED가 된 빠른 검사 세트도 검수 완료로 등급이 난다")
    void quickSetInspectedWhileWaitingStillGrades() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 윗면 긁힘 후보
        Defect d = scratch(set, Face.TOP);
        // 준비: 이 세트의 후보는 그 하나라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(d));
        // 준비: (먼저 확인) 검수를 기다려 등급이 안 나야 한다
        assertThat(grading.gradeIfReady(set)).isFalse();
        // 준비: 그사이 정합 결과가 와서 세트가 INSPECTED가 된다
        set.markInspected();
        // 준비: 관리자가 후보를 확정한다
        d.review(ReviewStatus.CONFIRMED, null, ADMIN_ID, Times.now());
        // 준비: 이 시점의 최신 세트 ID를 물으면 이 세트 ID를 돌려주게 한다
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        // 준비: 그 ID로 잠가 읽으면 이 세트를 돌려주게 한다
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        // 실행: 검수를 완료한다
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0003");

        // 확인: 세트는 등급 완료(GRADED)여야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
        // 확인: 나간 이벤트의 등급이 B여야 한다
        assertThat(sent().grade()).isEqualTo(Grade.B);
    }

    // [상황] 검수 완료를 같은 키로 두 번 보내고, 다른 키로 한 번 더 보낸 뒤, 완료된 세트에 결정을 다시 저장하려 한다.
    // [기대] 같은 키 재전송은 다시 매기지 않아 등급 이벤트는 한 번만 나간다. 다른 키와 완료 뒤 결정 저장은 409 REVIEW_CLOSED다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("같은 Idempotency-Key로 다시 완료하면 다시 매기지 않고, 다른 키면 409 REVIEW_CLOSED. 완료 뒤 결정 저장도 409")
    void completeIsIdempotent() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 윗면 긁힘 후보를 만들고
        Defect d = scratch(set, Face.TOP);
        // 확정해 둔다
        d.review(ReviewStatus.CONFIRMED, null, ADMIN_ID, Times.now());
        // 준비: 이 세트의 후보는 그 하나라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(d));
        // 준비: 이 시점의 최신 세트 ID를 물으면 이 세트 ID를 돌려주게 한다
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        // 준비: 그 ID로 잠가 읽으면 이 세트를 돌려주게 한다
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        // 실행: 요청 키 0001로 검수를 완료한다
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        // 실행: 같은 키로 한 번 더 보낸다
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        // (도구) verify(가짜).메서드(...) = 그 메서드가 (딱 한 번) 불렸어야 한다는 검사
        // 확인: 등급 이벤트는 한 번만 나갔어야 한다
        verify(events).publishGraded(any());

        // 실행+확인: 다른 키(0002)로 보내면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0002"))
                // 확인: "검수 이미 끝남"(409)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
        // 실행+확인: 완료 뒤 결정을 다시 저장하려 하면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.save(ADMIN, ADMIN_ID, set.getCheckpointId(),
                List.of(new Decision(d.getPublicId(), ReviewStatus.PENDING, null))))
                // 확인: "검수 이미 끝남"(409)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
    }

    // [상황] 결정 두 건을 함께 보내는데, 하나는 이 시점의 후보가 아닌 모르는 흠집 ID다.
    // [기대] 404로 거절하고 아무것도 바꾸지 않는다(맞는 ID의 후보도 PENDING 그대로, 일부만 저장되지 않음).
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("이 시점의 후보가 아닌 defectId가 하나라도 있으면 아무것도 바꾸지 않고 404")
    void unknownDefectChangesNothing() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 윗면 긁힘 후보
        Defect d = scratch(set, Face.TOP);
        // 준비: 이 세트의 후보는 그 하나라고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(d));
        // 준비: 이 시점의 최신 세트 ID를 물으면 이 세트 ID를 돌려주게 한다
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        // 준비: 그 ID로 잠가 읽으면 이 세트를 돌려주게 한다
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        // 실행+확인: 맞는 후보 + 모르는 ID("zzz…") 결정 두 건을 저장하면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.save(ADMIN, ADMIN_ID, set.getCheckpointId(), List.of(
                new Decision(d.getPublicId(), ReviewStatus.CONFIRMED, null),
                new Decision("zzzzzzzzzzzzzzzzzzzzz", ReviewStatus.CONFIRMED, null))))
                // 확인: 찾을 수 없음(404)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        // 확인: 맞는 후보도 대기(PENDING) 그대로여야 한다 (일부만 저장되지 않음)
        assertThat(d.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    // [상황] 사유 없는 기각을 보내고, 또 확정에 사유를 붙이거나 같은 흠집을 두 번 보낸다.
    // [기대] 400으로 거절하고 틀린 칸(items[0].reason, items[1].defectId)을 알려 준다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("기각에는 사유가 꼭 있고 다른 결정에는 없으며, 같은 흠집을 두 번 보내면 400")
    void validationRules() {
        // 실행+확인: 사유 없는 기각을 검사하면 예외가 나야 하고
        assertThatThrownBy(() -> ReviewService.validate(List.of(new Decision("a", ReviewStatus.DISMISSED, null))))
                // (도구) extracting(칸).containsExactly(...) = 목록 각 항목에서 그 칸만 뽑아, 정확히 이 값들이 이 순서로 있어야 한다는 검사
                // 확인: 틀린 칸은 items[0].reason이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errors()).extracting(DomainException.FieldError::field)
                                .containsExactly("items[0].reason"));
        // 실행+확인: 사유 붙은 확정 + 같은 흠집 a 두 번을 검사하면 예외가 나야 하고
        assertThatThrownBy(() -> ReviewService.validate(List.of(
                new Decision("a", ReviewStatus.CONFIRMED, DismissReason.TAPE),
                new Decision("a", ReviewStatus.PENDING, null))))
                // 확인: 틀린 칸은 items[0].reason과 items[1].defectId여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errors()).extracting(DomainException.FieldError::field)
                                .containsExactly("items[0].reason", "items[1].defectId"));
    }

    // [상황] 고객 토큰으로 검수 목록·검수 완료를 요청하고, 관리자가 목록을 "기각" 상태로 걸러 달라고 한다.
    // [기대] 고객은 403이다. 목록은 대기·완료로만 거를 수 있어 "기각"은 400이다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("관리자가 아니면 검수 목록·저장·완료 모두 403, 목록에 DISMISSED를 주면 400")
    void adminOnly() {
        // 준비: 이 렌탈을 가진 고객
        Caller customer = new Caller("CUSTOMER", null, null, List.of("rental-000000000000001"));
        // 실행+확인: 고객이 검수 대기 목록을 보면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.list(customer, ReviewStatus.PENDING, 20))
                // 확인: 권한 없음(403)이어야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        // 실행+확인: 고객이 검수 완료를 누르면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.complete(customer, "x", "check-00000000000001", "review-key-0001"))
                // 확인: 권한 없음(403)이어야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        // 실행+확인: 관리자가 목록을 "기각"으로 거르면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.list(ADMIN, ReviewStatus.DISMISSED, 20))
                // 확인: 입력값 검증 실패(400)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // [상황] 기각("반사")했던 후보를 다시 대기(PENDING)로 되돌리고, 확정하면서 사유를 붙여 본다.
    // [기대] 되돌리면 검수자·사유가 지워진다. 기각이 아닌 결정에 사유를 붙이면 거절한다.
    @Test
    @DisplayName("PENDING으로 되돌리면 검수자·시각이 지워지고, 기각이 아닌 결정에 사유를 주면 거절한다")
    void revertClearsReviewer() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 윗면 긁힘 후보
        Defect d = scratch(set, Face.TOP);
        // 준비: "반사"로 기각한다
        d.review(ReviewStatus.DISMISSED, DismissReason.REFLECTION, ADMIN_ID, Times.now());
        // 준비: (먼저 확인) 기각 상태여야 한다
        assertThat(d.isDismissed()).isTrue();
        // 실행: 다시 대기(PENDING)로 되돌린다
        d.review(ReviewStatus.PENDING, null, ADMIN_ID, Times.now());
        // 확인: 검수자가 지워져야 한다
        assertThat(d.getReviewedBy()).isNull();
        // 확인: 기각 사유도 지워져야 한다
        assertThat(d.getDismissReason()).isNull();
        // 준비: 지금 시각
        OffsetDateTime at = Times.now();
        // (도구) isInstanceOf(종류) = 예외가 그 종류여야 한다는 검사
        // 실행+확인: 확정하면서 사유를 붙이면 잘못된 값 예외가 나야 한다
        assertThatThrownBy(() -> d.review(ReviewStatus.CONFIRMED, DismissReason.PART, ADMIN_ID, at))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // [상황] 검수를 끈 설정(app.review.required=false)에서 AI 후보가 있는 세트에 탐지 결과가 왔다.
    // [기대] 예전처럼 바로 등급이 나고 이벤트에 검수 기록은 없다(비상용 되돌리기).
    @Test
    @DisplayName("검수 끔(app.review.required=false)이면 후보가 있어도 바로 등급(이전 동작)")
    void reviewOffGradesImmediately() {
        // 준비: 검수 끔(false) 설정으로 등급 서비스를 만든다
        GradingService off = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, events, false);
        // 준비: 탐지 결과까지 받은 빠른 검사 세트를 만든다
        CaptureSet set = readySet();
        // 준비: 윗면 AI 후보 하나가 있다고 답하게 한다
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));
        // 실행+확인: 후보가 있어도 바로 등급이 나야 한다
        assertThat(off.gradeIfReady(set)).isTrue();
        // 확인: 이벤트에 검수 기록이 없어야 한다
        assertThat(sent().review()).isNull();
    }

    // 테스트 도구: 보낸 "시점 등급 완료" 이벤트를 꺼낸다.
    private CheckpointGradedEvent sent() {
        // (도구) ArgumentCaptor = 가짜에게 넘겨진 인자를 붙잡아 두는 도구 (무엇을 보냈는지 꺼내 보려고)
        // 등급 이벤트를 붙잡을 도구를 만든다
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        // 이벤트 발행이 불렸는지 확인하면서 넘겨진 이벤트를 붙잡는다
        verify(events).publishGraded(sent.capture());
        // 붙잡은 이벤트를 돌려준다
        return sent.getValue();
    }

    // 테스트 도구: 그 면 CAM1·L1 사진에 AI 긁힘 후보(확신도 0.3)를 만든다.
    private static Defect scratch(CaptureSet set, Face face) {
        // 그 면 사진에 긁힘 후보(확신도 0.3, 모델 aug-v002)를 만들어 돌려준다
        return Defect.fromBooth(photo(set, face), DefectClass.SCRATCH, BOX, 0.3, "aug-v002");
    }

    // 테스트 도구: 그 면 CAM1·L1 사진에 AI 찍힘 후보(확신도 0.3)를 만든다.
    private static Defect dent(CaptureSet set, Face face) {
        // 그 면 사진에 찍힘 후보를 만들어 돌려준다
        return Defect.fromBooth(photo(set, face), DefectClass.DENT, BOX, 0.3, "aug-v002");
    }

    // 테스트 도구: 다 올리고 탐지 결과까지 받은 빠른 검사 세트를 만든다.
    private static CaptureSet readySet() {
        // 건너뛴 면 없이 아래 도우미를 부른다
        return readySet(Map.of());
    }

    // 테스트 도구: 건너뛴 면을 정해, 다 올리고 탐지 결과까지 받은 빠른 검사 세트를 만든다.
    private static CaptureSet readySet(Map<Face, String> skipped) {
        // 다 올린 세트를 만들고
        CaptureSet set = completedSet(skipped);
        // 탐지 결과를 받았다고 표시한다
        set.markDetectionsReceived();
        // 검수할 수 있는 세트를 돌려준다
        return set;
    }

    // 테스트 도구: 다 올렸지만 탐지 결과는 아직 없는 빠른 검사 세트를 만든다.
    private static CaptureSet completedSet(Map<Face, String> skipped) {
        // 부스 빠른 검사 세트를 12장 목록으로 연다
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        // "올라온 사진" ID를 모을 빈 집합
        Set<String> present = new HashSet<>();
        // 모든 사진을 "올라옴"에 넣는다
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        // 받은 건너뛴 면 정보로 세트를 완료한다
        set.complete(present, skipped, "key-" + present.hashCode(), SHA);
        // 완료된 세트를 돌려준다
        return set;
    }

    // 테스트 도구: 세트에서 그 면의 CAM1·L1 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face) {
        // 세트 사진 중 그 면·CAM1·L1인 첫 사진을 돌려준다 (없으면 예외)
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1 && p.getLight() == Light.L1)
                .findFirst().orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 모든 사진에 같은 촬영 시각을 쓴다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-08T10:00:00+09:00");
        // 바닥·윗면마다
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            // 조명 L1·L2·L3마다
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                // CAM1 사진 한 장
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                // CAM2 사진 한 장 → 2 × 3 × 2 = 12장
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        // 완성된 12장 목록을 돌려준다
        return specs;
    }
}
