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

    private static final String SHA = "c".repeat(64);
    private static final String ADMIN_ID = "admin00000000000000001";
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    private DefectRepository defects;
    private GradeResultRepository grades;
    private GradeEventPublisher events;
    private CaptureSetRepository sets;
    private GradingService grading;
    private ReviewService reviews;

    // 테스트 도구: 검수 켬(후보 없는 세트는 기다리지 않음) 설정으로 등급·검수 서비스를 가짜 저장소와 함께 만든다.
    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        grades = mock(GradeResultRepository.class);
        events = mock(GradeEventPublisher.class);
        sets = mock(CaptureSetRepository.class);
        when(grades.findByCheckpointId(anyString())).thenReturn(Optional.empty());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        grading = new GradingService(defects, mock(DefectMatchRepository.class), mock(FaceAlignmentRepository.class),
                grades, events, true);
        reviews = new ReviewService(sets, defects, mock(ReviewQueueRepository.class), grading,
                mock(InspectionQueryService.class), mock(FaceAlignmentRepository.class));
    }

    // [상황] 윗면에 AI 긁힘 후보가 있는 빠른 검사 세트에 탐지 결과가 왔다.
    // [기대] 검수 완료 전까지 등급을 매기지 않는다(상태 UPLOADED 그대로, 이벤트 없음).
    @Test
    @DisplayName("AI 후보가 있는 세트는 탐지가 와도 검수 완료 전까지 등급을 매기지 않는다")
    void holdsUntilReview() {
        CaptureSet set = readySet();
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));

        assertThat(grading.gradeIfReady(set)).isFalse();

        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        verify(events, never()).publishGraded(any());
    }

    // [상황] AI 후보가 하나도 없는 세트에 탐지 결과가 왔다.
    // [기대] 검수 없이 바로 등급 A가 나고, 이벤트(스키마 1.3)에 검수 기록(review)은 없다.
    @Test
    @DisplayName("후보가 하나도 없는 세트는 검수 없이 바로 등급이 나고 이벤트에 review가 없다")
    void noCandidatesGradesImmediately() {
        CaptureSet set = readySet();
        when(defects.findBySet(set)).thenReturn(List.of());

        assertThat(grading.gradeIfReady(set)).isTrue();

        CheckpointGradedEvent sent = sent();
        assertThat(sent.grade()).isEqualTo(Grade.A);
        assertThat(sent.review()).isNull();
        assertThat(sent.schemaVersion()).isEqualTo("1.3");
    }

    // [상황] 윗면을 "덮개 고장"으로 건너뛰었는데, 그 면에 탐지가 하나 와 있다.
    // [기대] 건너뛴 면의 탐지는 검수 후보가 아니라서 검수를 기다리지 않고 바로 등급이 난다.
    @Test
    @DisplayName("건너뛴 면의 탐지는 후보가 아니라 검수를 기다리지 않는다")
    void skippedFaceIsNotCandidate() {
        CaptureSet set = readySet(Map.of(Face.TOP, "덮개 고장"));
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));

        assertThat(GradingService.reviewCandidates(set, defects.findBySet(set))).isEmpty();
        assertThat(grading.gradeIfReady(set)).isTrue();
    }

    // [상황] 윗면 찍힘 후보는 "먼지"로 기각, 바닥 긁힘 후보는 확정하고 검수를 완료한다.
    // [기대] 결정만 저장했을 때는 등급 이벤트가 없고, 검수 완료 때 찍힘을 뺀 긁힘 하나로 B가 난다(찍힘이 남았으면 C).
    //        이벤트의 검수 기록에 검수한 관리자, 확정 1·기각 1이 실리고 세트는 등급 완료(GRADED)가 된다.
    @Test
    @DisplayName("기각(먼지·스티커)한 후보는 등급에서 빠지고, 확정한 후보만 센다. 이벤트 review에 검수자와 건수가 실린다")
    void dismissedExcludedFromGrade() {
        CaptureSet set = readySet();
        Defect dust = dent(set, Face.TOP);
        Defect real = scratch(set, Face.BOT);
        when(defects.findBySet(set)).thenReturn(List.of(dust, real));
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        reviews.save(ADMIN, ADMIN_ID, set.getCheckpointId(), List.of(
                new Decision(dust.getPublicId(), ReviewStatus.DISMISSED, DismissReason.DUST),
                new Decision(real.getPublicId(), ReviewStatus.CONFIRMED, null)));
        verify(events, never()).publishGraded(any());
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");

        CheckpointGradedEvent sent = sent();
        assertThat(sent.grade()).isEqualTo(Grade.B); // 찍힘(C)은 기각돼 긁힘 하나만 남는다
        assertThat(sent.review()).isNotNull();
        assertThat(sent.review().reviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(sent.review().confirmed()).isEqualTo(1);
        assertThat(sent.review().dismissed()).isEqualTo(1);
        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
        assertThat(dust.getReviewedBy()).isEqualTo(ADMIN_ID);
    }

    // [상황] 후보 2개를 아직 하나도 정하지 않은 채 검수 완료를 누른다.
    // [기대] 409 REVIEW_PENDING_REMAINS와 남은 수(2)를 알려 주고, 검수 완료 시각은 남기지 않는다.
    @Test
    @DisplayName("PENDING이 남아 있으면 검수 완료는 409 REVIEW_PENDING_REMAINS(남은 수 pending)")
    void completeWithPendingIs409() {
        CaptureSet set = readySet();
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP), scratch(set, Face.BOT)));
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_PENDING_REMAINS);
                    assertThat(e.properties()).containsEntry("pending", 2L);
                });
        assertThat(set.getReviewCompletedAt()).isNull();
    }

    // [상황] 부스 탐지 결과가 오기 전에 검수 완료를 누르고, 탐지가 온 뒤 후보가 없는 상태에서 다시 누른다.
    // [기대] 처음엔 409 REVIEW_NOT_READY(아직 검수할 수 없음), 후보 없는 세트는 이미 바로 등급이 나므로 409 REVIEW_CLOSED다.
    @Test
    @DisplayName("부스 탐지가 오기 전에는 검수 완료가 409 REVIEW_NOT_READY, 후보 없는 세트는 409 REVIEW_CLOSED")
    void notReadyOrNoCandidates() {
        CaptureSet waiting = completedSet(Map.of());
        when(sets.findLatestPublicIdByCheckpointId(waiting.getCheckpointId())).thenReturn(Optional.of(waiting.getPublicId()));
        when(sets.findForUpdate(waiting.getPublicId())).thenReturn(Optional.of(waiting));
        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, waiting.getCheckpointId(), "review-key-0001"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_NOT_READY));

        waiting.markDetectionsReceived();
        when(defects.findBySet(waiting)).thenReturn(List.of());
        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, waiting.getCheckpointId(), "review-key-0001"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
    }

    // [상황] 빠른 검사 세트가 검수를 기다리는 사이 면 펼치기(정합) 결과가 와서 상태가 INSPECTED로 바뀌었다. 그 뒤 후보를 확정하고 검수를 완료한다.
    // [기대] 그래도 등급 완료(GRADED)가 되고 등급 B가 난다(상태가 바뀌었다고 등급이 막히지 않음).
    @Test
    @DisplayName("검수를 기다리는 사이 정합 결과가 와서 INSPECTED가 된 빠른 검사 세트도 검수 완료로 등급이 난다")
    void quickSetInspectedWhileWaitingStillGrades() {
        CaptureSet set = readySet();
        Defect d = scratch(set, Face.TOP);
        when(defects.findBySet(set)).thenReturn(List.of(d));
        assertThat(grading.gradeIfReady(set)).isFalse();
        set.markInspected();
        d.review(ReviewStatus.CONFIRMED, null, ADMIN_ID, Times.now());
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0003");

        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
        assertThat(sent().grade()).isEqualTo(Grade.B);
    }

    // [상황] 검수 완료를 같은 키로 두 번 보내고, 다른 키로 한 번 더 보낸 뒤, 완료된 세트에 결정을 다시 저장하려 한다.
    // [기대] 같은 키 재전송은 다시 매기지 않아 등급 이벤트는 한 번만 나간다. 다른 키와 완료 뒤 결정 저장은 409 REVIEW_CLOSED다.
    @Test
    @DisplayName("같은 Idempotency-Key로 다시 완료하면 다시 매기지 않고, 다른 키면 409 REVIEW_CLOSED. 완료 뒤 결정 저장도 409")
    void completeIsIdempotent() {
        CaptureSet set = readySet();
        Defect d = scratch(set, Face.TOP);
        d.review(ReviewStatus.CONFIRMED, null, ADMIN_ID, Times.now());
        when(defects.findBySet(set)).thenReturn(List.of(d));
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        verify(events).publishGraded(any());

        assertThatThrownBy(() -> reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0002"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
        assertThatThrownBy(() -> reviews.save(ADMIN, ADMIN_ID, set.getCheckpointId(),
                List.of(new Decision(d.getPublicId(), ReviewStatus.PENDING, null))))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
    }

    // [상황] 결정 두 건을 함께 보내는데, 하나는 이 시점의 후보가 아닌 모르는 흠집 ID다.
    // [기대] 404로 거절하고 아무것도 바꾸지 않는다(맞는 ID의 후보도 PENDING 그대로, 일부만 저장되지 않음).
    @Test
    @DisplayName("이 시점의 후보가 아닌 defectId가 하나라도 있으면 아무것도 바꾸지 않고 404")
    void unknownDefectChangesNothing() {
        CaptureSet set = readySet();
        Defect d = scratch(set, Face.TOP);
        when(defects.findBySet(set)).thenReturn(List.of(d));
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));

        assertThatThrownBy(() -> reviews.save(ADMIN, ADMIN_ID, set.getCheckpointId(), List.of(
                new Decision(d.getPublicId(), ReviewStatus.CONFIRMED, null),
                new Decision("zzzzzzzzzzzzzzzzzzzzz", ReviewStatus.CONFIRMED, null))))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(d.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    // [상황] 사유 없는 기각을 보내고, 또 확정에 사유를 붙이거나 같은 흠집을 두 번 보낸다.
    // [기대] 400으로 거절하고 틀린 칸(items[0].reason, items[1].defectId)을 알려 준다.
    @Test
    @DisplayName("기각에는 사유가 꼭 있고 다른 결정에는 없으며, 같은 흠집을 두 번 보내면 400")
    void validationRules() {
        assertThatThrownBy(() -> ReviewService.validate(List.of(new Decision("a", ReviewStatus.DISMISSED, null))))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errors()).extracting(DomainException.FieldError::field)
                                .containsExactly("items[0].reason"));
        assertThatThrownBy(() -> ReviewService.validate(List.of(
                new Decision("a", ReviewStatus.CONFIRMED, DismissReason.TAPE),
                new Decision("a", ReviewStatus.PENDING, null))))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errors()).extracting(DomainException.FieldError::field)
                                .containsExactly("items[0].reason", "items[1].defectId"));
    }

    // [상황] 고객 토큰으로 검수 목록·검수 완료를 요청하고, 관리자가 목록을 "기각" 상태로 걸러 달라고 한다.
    // [기대] 고객은 403이다. 목록은 대기·완료로만 거를 수 있어 "기각"은 400이다.
    @Test
    @DisplayName("관리자가 아니면 검수 목록·저장·완료 모두 403, 목록에 DISMISSED를 주면 400")
    void adminOnly() {
        Caller customer = new Caller("CUSTOMER", null, null, List.of("rental-000000000000001"));
        assertThatThrownBy(() -> reviews.list(customer, ReviewStatus.PENDING, 20))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> reviews.complete(customer, "x", "check-00000000000001", "review-key-0001"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> reviews.list(ADMIN, ReviewStatus.DISMISSED, 20))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // [상황] 기각("반사")했던 후보를 다시 대기(PENDING)로 되돌리고, 확정하면서 사유를 붙여 본다.
    // [기대] 되돌리면 검수자·사유가 지워진다. 기각이 아닌 결정에 사유를 붙이면 거절한다.
    @Test
    @DisplayName("PENDING으로 되돌리면 검수자·시각이 지워지고, 기각이 아닌 결정에 사유를 주면 거절한다")
    void revertClearsReviewer() {
        CaptureSet set = readySet();
        Defect d = scratch(set, Face.TOP);
        d.review(ReviewStatus.DISMISSED, DismissReason.REFLECTION, ADMIN_ID, Times.now());
        assertThat(d.isDismissed()).isTrue();
        d.review(ReviewStatus.PENDING, null, ADMIN_ID, Times.now());
        assertThat(d.getReviewedBy()).isNull();
        assertThat(d.getDismissReason()).isNull();
        OffsetDateTime at = Times.now();
        assertThatThrownBy(() -> d.review(ReviewStatus.CONFIRMED, DismissReason.PART, ADMIN_ID, at))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // [상황] 검수를 끈 설정(app.review.required=false)에서 AI 후보가 있는 세트에 탐지 결과가 왔다.
    // [기대] 예전처럼 바로 등급이 나고 이벤트에 검수 기록은 없다(비상용 되돌리기).
    @Test
    @DisplayName("검수 끔(app.review.required=false)이면 후보가 있어도 바로 등급(이전 동작)")
    void reviewOffGradesImmediately() {
        GradingService off = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, events, false);
        CaptureSet set = readySet();
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));
        assertThat(off.gradeIfReady(set)).isTrue();
        assertThat(sent().review()).isNull();
    }

    // 테스트 도구: 보낸 "시점 등급 완료" 이벤트를 꺼낸다.
    private CheckpointGradedEvent sent() {
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        return sent.getValue();
    }

    // 테스트 도구: 그 면 CAM1·L1 사진에 AI 긁힘 후보(확신도 0.3)를 만든다.
    private static Defect scratch(CaptureSet set, Face face) {
        return Defect.fromBooth(photo(set, face), DefectClass.SCRATCH, BOX, 0.3, "aug-v002");
    }

    // 테스트 도구: 그 면 CAM1·L1 사진에 AI 찍힘 후보(확신도 0.3)를 만든다.
    private static Defect dent(CaptureSet set, Face face) {
        return Defect.fromBooth(photo(set, face), DefectClass.DENT, BOX, 0.3, "aug-v002");
    }

    // 테스트 도구: 다 올리고 탐지 결과까지 받은 빠른 검사 세트를 만든다.
    private static CaptureSet readySet() {
        return readySet(Map.of());
    }

    // 테스트 도구: 건너뛴 면을 정해, 다 올리고 탐지 결과까지 받은 빠른 검사 세트를 만든다.
    private static CaptureSet readySet(Map<Face, String> skipped) {
        CaptureSet set = completedSet(skipped);
        set.markDetectionsReceived();
        return set;
    }

    // 테스트 도구: 다 올렸지만 탐지 결과는 아직 없는 빠른 검사 세트를 만든다.
    private static CaptureSet completedSet(Map<Face, String> skipped) {
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, skipped, "key-" + present.hashCode(), SHA);
        return set;
    }

    // 테스트 도구: 세트에서 그 면의 CAM1·L1 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1 && p.getLight() == Light.L1)
                .findFirst().orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        List<PhotoSpec> specs = new ArrayList<>();
        OffsetDateTime at = OffsetDateTime.parse("2026-11-08T10:00:00+09:00");
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        return specs;
    }
}
