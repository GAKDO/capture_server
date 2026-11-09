package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.ReviewService.HumanDefect;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DefectSource;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;
import com.gakdo.trace.capture.inspection.infra.ReviewQueueRepository;

/** 놓친 흠집 직접 추가(11/9): 관리자가 그린 흠집이 등급에 들어가고, 후보 없는 세트도 검수를 기다린다. Docker 없이 돈다. */
class HumanDefectTest {

    private static final String SHA = "c".repeat(64);
    private static final String ADMIN_ID = "admin00000000000000001";
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    private DefectRepository defects;
    private GradeEventPublisher events;
    private CaptureSetRepository sets;
    private GradingService grading;
    private ReviewService reviews;
    private final List<Defect> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        GradeResultRepository grades = mock(GradeResultRepository.class);
        events = mock(GradeEventPublisher.class);
        sets = mock(CaptureSetRepository.class);
        when(grades.findByCheckpointId(anyString())).thenReturn(Optional.empty());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(defects.findBySet(any())).thenAnswer(inv -> List.copyOf(stored));
        when(defects.save(any(Defect.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        doAnswer(inv -> stored.remove(inv.<Defect>getArgument(0))).when(defects).delete(any(Defect.class));
        grading = new GradingService(defects, mock(DefectMatchRepository.class), mock(FaceAlignmentRepository.class),
                grades, events, true, true);
        reviews = new ReviewService(sets, defects, mock(ReviewQueueRepository.class), grading,
                mock(InspectionQueryService.class), mock(FaceAlignmentRepository.class));
    }

    @Test
    @DisplayName("AI 후보가 없는 세트도 바로 등급을 매기지 않고 검수를 기다리며, 검수 칸이 열린다")
    void emptySetWaitsForReview() {
        CaptureSet set = lockable(readySet(Map.of()));

        assertThat(grading.gradeIfReady(set)).isFalse();
        assertThat(ReviewService.open(set, stored, true, true)).isTrue();
        verify(events, never()).publishGraded(any());

        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        assertThat(sent().grade()).isEqualTo(Grade.A);
    }

    @Test
    @DisplayName("AI가 놓친 찍힘을 관리자가 그려 더하면 HUMAN·확정으로 저장되고 검수 완료 때 등급(C)에 들어간다")
    void addedDefectCountsInGrade() {
        CaptureSet set = lockable(readySet(Map.of()));

        reviews.add(ADMIN, ADMIN_ID, set.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX));

        assertThat(stored).singleElement().satisfies(d -> {
            assertThat(d.getSource()).isEqualTo(DefectSource.HUMAN);
            assertThat(d.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
            assertThat(d.getReviewedBy()).isEqualTo(ADMIN_ID);
            assertThat(d.getConfidence()).isEqualTo(1.0);
            assertThat(d.getModelVersion()).isEqualTo(Defect.HUMAN_MODEL);
        });
        assertThat(GradingService.reviewCandidates(set, stored)).isEmpty(); // AI 후보 수에는 세지 않는다
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        assertThat(sent().grade()).isEqualTo(Grade.C);
        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
    }

    @Test
    @DisplayName("직접 더한 흠집은 지울 수 있지만 AI 후보는 지울 수 없다(404)")
    void removeOnlyHuman() {
        CaptureSet set = lockable(readySet(Map.of()));
        Defect ai = Defect.fromBooth(photo(set, Face.BOT), DefectClass.SCRATCH, BOX, 0.3, "aug-v004");
        stored.add(ai);
        reviews.add(ADMIN, ADMIN_ID, set.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.SCRATCH, BOX));
        Defect human = stored.get(1);

        assertThatThrownBy(() -> reviews.remove(ADMIN, ADMIN_ID, set.getCheckpointId(), ai.getPublicId()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        reviews.remove(ADMIN, ADMIN_ID, set.getCheckpointId(), human.getPublicId());
        assertThat(stored).containsExactly(ai);
    }

    @Test
    @DisplayName("건너뛴 면·세트에 없는 사진·잘못된 다각형은 400, 탐지 전은 409 REVIEW_NOT_READY, 등급 뒤는 409 REVIEW_CLOSED")
    void rejectsBadAdditions() {
        CaptureSet skipped = lockable(readySet(Map.of(Face.TOP, "덮개 고장")));
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, skipped.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, skipped.getCheckpointId(),
                new HumanDefect(Face.KB, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> ReviewService.validatePolygon(List.of(List.of(1.0, 1.0), List.of(-2.0, 3.0),
                List.of(4.0, 4.0))))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        CaptureSet waiting = lockable(completedSet(Map.of()));
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, waiting.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_NOT_READY));
        assertThat(ReviewService.open(waiting, stored, true, true)).isFalse();

        CaptureSet graded = lockable(readySet(Map.of()));
        reviews.complete(ADMIN, ADMIN_ID, graded.getCheckpointId(), "review-key-0009");
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, graded.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
        assertThat(ReviewService.open(graded, stored, true, true)).isFalse();
    }

    @Test
    @DisplayName("후보 없는 세트를 기다리지 않는 설정이면 예전처럼 바로 등급이 나고 검수 칸이 닫힌다")
    void holdEmptyOff() {
        GradingService off = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), mock(GradeResultRepository.class), events, true, false);
        CaptureSet set = readySet(Map.of());
        assertThat(ReviewService.open(set, stored, true, false)).isFalse();
        assertThat(off.isHoldEmpty()).isFalse();
    }

    @Test
    @DisplayName("대기 목록 SQL은 후보 없는 세트도 0건으로 싣되 탐지·정합 전 세트는 뺀다")
    void queueSqlIncludesEmptySets() {
        String sql = ReviewQueueRepository.pendingSql(true);
        assertThat(sql).contains("LEFT JOIN per_set").contains("coalesce(ps.pending, 0)").contains("coalesce(ps.confirmed, 0)").contains("coalesce(ps.dismissed, 0)")
                .contains("detections_received_at IS NOT NULL").contains("LIMIT :limit");
        assertThat(ReviewQueueRepository.pendingSql(false)).doesNotContain("LEFT JOIN per_set");
        assertThat(ReviewQueueRepository.doneSql(true)).contains("LEFT JOIN per_set").contains("review_completed_at DESC");
    }

    private CaptureSet lockable(CaptureSet set) {
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));
        return set;
    }

    private CheckpointGradedEvent sent() {
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        return sent.getValue();
    }

    private static int seq;

    private static CaptureSet readySet(Map<Face, String> skipped) {
        CaptureSet set = completedSet(skipped);
        set.markDetectionsReceived();
        return set;
    }

    private static CaptureSet completedSet(Map<Face, String> skipped) {
        seq++;
        CaptureSet set = CaptureSet.open("rental-000000000000001", String.format("check-%015d", seq),
                CaptureSource.BOOTH, "booth-1", null, CaptureProfile.QUICK, specs());
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
        OffsetDateTime at = OffsetDateTime.parse("2026-11-09T10:00:00+09:00");
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        return specs;
    }
}
