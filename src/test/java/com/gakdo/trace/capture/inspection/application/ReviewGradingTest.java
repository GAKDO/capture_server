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
                mock(InspectionQueryService.class));
    }

    @Test
    @DisplayName("AI 후보가 있는 세트는 탐지가 와도 검수 완료 전까지 등급을 매기지 않는다")
    void holdsUntilReview() {
        CaptureSet set = readySet();
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));

        assertThat(grading.gradeIfReady(set)).isFalse();

        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        verify(events, never()).publishGraded(any());
    }

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

    @Test
    @DisplayName("건너뛴 면의 탐지는 후보가 아니라 검수를 기다리지 않는다")
    void skippedFaceIsNotCandidate() {
        CaptureSet set = readySet(Map.of(Face.TOP, "덮개 고장"));
        when(defects.findBySet(set)).thenReturn(List.of(scratch(set, Face.TOP)));

        assertThat(GradingService.reviewCandidates(set, defects.findBySet(set))).isEmpty();
        assertThat(grading.gradeIfReady(set)).isTrue();
    }

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
        assertThatThrownBy(() -> d.review(ReviewStatus.CONFIRMED, DismissReason.PART, ADMIN_ID, Times.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

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

    private CheckpointGradedEvent sent() {
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        return sent.getValue();
    }

    private static Defect scratch(CaptureSet set, Face face) {
        return Defect.fromBooth(photo(set, face), DefectClass.SCRATCH, BOX, 0.3, "aug-v002");
    }

    private static Defect dent(CaptureSet set, Face face) {
        return Defect.fromBooth(photo(set, face), DefectClass.DENT, BOX, 0.3, "aug-v002");
    }

    private static CaptureSet readySet() {
        return readySet(Map.of());
    }

    private static CaptureSet readySet(Map<Face, String> skipped) {
        CaptureSet set = completedSet(skipped);
        set.markDetectionsReceived();
        return set;
    }

    private static CaptureSet completedSet(Map<Face, String> skipped) {
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, skipped, "key-" + present.hashCode(), SHA);
        return set;
    }

    private static Photo photo(CaptureSet set, Face face) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1 && p.getLight() == Light.L1)
                .findFirst().orElseThrow();
    }

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
