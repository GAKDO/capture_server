package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.SettlementService.SettlementView;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DefectGroups;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/** 반납 정산서(11/10): 반납 시점만, 검수·등급 뒤에, 앞 시점보다 늘어난 흠집만 매긴다. 저장소는 가짜라 Docker 없이 돈다. */
class SettlementServiceTest {

    private static final String SHA = "b".repeat(64);
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    private static final List<List<Double>> FAR = List.of(List.of(900.0, 600.0), List.of(940.0, 600.0),
            List.of(940.0, 620.0), List.of(900.0, 620.0));
    private static final Caller ADMIN = new Caller("ADMIN", null, null, null);

    private DefectRepository defects;
    private GradeResultRepository grades;
    private SettlementService service;

    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        grades = mock(GradeResultRepository.class);
        GradingService grading = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, mock(GradeEventPublisher.class));
        service = new SettlementService(mock(CaptureSetRepository.class), grades, defects, grading, 300_000);
    }

    @Test
    @DisplayName("반납 빠른 검사는 앞 시점보다 늘어난 흠집만 규칙표 금액으로 매긴다")
    void returnChargesOnlyIncrease() {
        CaptureSet before = completedQuickSet("check-00000000000002");
        when(defects.findBySet(before)).thenReturn(List.of(
                Defect.fromBooth(photo(before, Face.TOP, Camera.CAM1), DefectClass.SCRATCH, BOX, 0.9, "v")));
        CaptureSet ret = completedQuickSet("check-00000000000003");
        when(defects.findBySet(ret)).thenReturn(List.of(
                Defect.fromBooth(photo(ret, Face.TOP, Camera.CAM1), DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(photo(ret, Face.TOP, Camera.CAM1), DefectClass.DENT, FAR, 0.8, "v")));
        graded(ret, Grade.C, 2);
        GradeResult prev = new GradeResult(before.getCheckpointId());
        prev.recordGrade(before, Grade.B, "grade-rules-v1", List.of(), List.of());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.of(prev));

        SettlementView view = service.get(ADMIN, ret.getCheckpointId());

        assertThat(view.status()).isEqualTo("READY");
        assertThat(view.checkpointType()).isEqualTo("RET");
        assertThat(view.lines()).extracting(SettlementService.LineView::defectClass).containsExactly("dent");
        assertThat(view.total()).isEqualTo(30_000);
        assertThat(view.grade()).isEqualTo("C");
    }

    @Test
    @DisplayName("반납이 전체 검사여도 앞 시점(수령)이 빠른 검사면 출고와 위치 비교하지 않고 수령과 수로 견줘 배송 중 손상을 빼다")
    void fullReturnAfterQuickReceiptComparesWithReceipt() {
        CaptureSet rcv = completedQuickSet("check-00000000000002");
        CaptureSet ret = completedSet("check-00000000000003", CaptureProfile.FULL);
        graded(ret, Grade.B, 2);
        GradeResult prev = new GradeResult(rcv.getCheckpointId());
        prev.recordGrade(rcv, Grade.B, "grade-rules-v1", List.of(), List.of());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.of(prev));
        // 출고는 전체 검사였다(예전 코드는 이것만 보고 위치 비교를 썼다)
        CaptureSet out = completedSet("check-00000000000001", CaptureProfile.FULL);
        GradeResult outGrade = new GradeResult(out.getCheckpointId());
        outGrade.recordGrade(out, Grade.A, "grade-rules-v1", List.of(), List.of());
        when(grades.findFirstByCaptureSetRentalIdAndCaptureSetProfileAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(),
                any(), anyString())).thenReturn(Optional.of(outGrade));
        // 반납 전체 검사의 위치 비교는 출고와 견줘 배송 중 생긴 찍힘을 NEW로 본다. 수령(빠른 검사)에 이미 같은 찍힘이 있다
        GradingService grading = mock(GradingService.class);
        when(grading.gradedGroups(ret)).thenReturn(List.of(
                new DefectGroups.Group(Face.TOP, DefectClass.DENT, 6.0, true, List.of("ret-dent"))));
        when(grading.gradedGroups(rcv)).thenReturn(List.of(
                new DefectGroups.Group(Face.TOP, DefectClass.DENT, 6.0, null, List.of("rcv-dent"))));
        when(defects.findBySet(any())).thenReturn(List.of());
        SettlementService svc = new SettlementService(mock(CaptureSetRepository.class), grades, defects, grading,
                300_000);

        SettlementView view = svc.get(ADMIN, ret.getCheckpointId());

        assertThat(view.lines()).isEmpty();
        assertThat(view.total()).isZero();
        assertThat(view.notes()).contains("앞 시점과 같은 수만큼의 흠집 1개는 빼고 계산");
    }

    @Test
    @DisplayName("수령 시점은 배송 중 손상이라 정산하지 않는다")
    void receiptIsNotApplicable() {
        CaptureSet rcv = completedQuickSet("check-00000000000002");
        graded(rcv, Grade.B, 1);
        SettlementView view = service.get(ADMIN, rcv.getCheckpointId());
        assertThat(view.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(view.checkpointType()).isEqualTo("RCV");
        assertThat(view.total()).isZero();
    }

    @Test
    @DisplayName("검수·등급 전이면 PENDING으로 금액 없이 돌려준다")
    void pendingBeforeGrade() {
        CaptureSet ret = completedQuickSet("check-00000000000003");
        when(grades.findByCheckpointId(ret.getCheckpointId())).thenReturn(Optional.empty());
        CaptureSetRepository sets = mock(CaptureSetRepository.class);
        when(sets.findFirstByCheckpointIdOrderByIdDesc(ret.getCheckpointId())).thenReturn(Optional.of(ret));
        GradingService grading = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, mock(GradeEventPublisher.class));
        SettlementView view = new SettlementService(sets, grades, defects, grading, 300_000)
                .get(ADMIN, ret.getCheckpointId());
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.lines()).isEmpty();
    }

    private void graded(CaptureSet set, Grade grade, long earlier) {
        GradeResult result = new GradeResult(set.getCheckpointId());
        result.recordGrade(set, grade, "grade-rules-v1", List.of(), List.of());
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(result));
        when(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(anyString(),
                eq(set.getCheckpointId()), any())).thenReturn(earlier);
    }

    private static CaptureSet completedQuickSet(String checkpointId) {
        return completedSet(checkpointId, CaptureProfile.QUICK);
    }

    private static CaptureSet completedSet(String checkpointId, CaptureProfile profile) {
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, CaptureSource.BOOTH,
                "booth-1", null, profile, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        return set;
    }

    private static Photo photo(CaptureSet set, Face face, Camera camera) {
        return photo(set, face, camera, Light.L1);
    }

    private static Photo photo(CaptureSet set, Face face, Camera camera, Light light) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == camera && p.getLight() == light).findFirst()
                .orElseThrow();
    }

    private static List<PhotoSpec> specs() {
        List<PhotoSpec> specs = new ArrayList<>();
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        return specs;
    }
}
