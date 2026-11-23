// 이 파일이 확인하는 것: 반납 정산서(11/10). 관리자 화면에서 반납 시점의 차감 금액을 보여 준다.
// 정산은 반납 시점에만 하고, 검수·등급이 끝난 뒤에만 금액을 내며, 앞 시점보다 늘어난 흠집만 매긴다.
// 기기 기준가는 2,057,693원(발표 예시 기기)으로 둔다.

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

    // 테스트 도구: 가짜 흠집·등급 저장소로 정산 서비스를 만든다.
    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        grades = mock(GradeResultRepository.class);
        GradingService grading = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, mock(GradeEventPublisher.class));
        service = new SettlementService(mock(CaptureSetRepository.class), grades, defects, grading, 2_057_693L);
    }

    // [상황] 앞 시점(수령)에는 윗면 긁힘 1개, 반납에는 같은 긁힘 + 떨어진 자리 찍힘 1개가 있다. 반납 등급은 C다.
    // [기대] 정산서는 "준비됨"(READY), 시점은 반납(RET)이고, 새로 늘어난 찍힘 하나만 31,000원으로 매긴다.
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
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(anyString(),
                anyString(), any()))
                .thenReturn(Optional.of(prev));

        SettlementView view = service.get(ADMIN, ret.getCheckpointId());

        assertThat(view.status()).isEqualTo("READY");
        assertThat(view.checkpointType()).isEqualTo("RET");
        assertThat(view.lines()).extracting(SettlementService.LineView::defectClass).containsExactly("dent");
        assertThat(view.total()).isEqualTo(31_000);
        assertThat(view.grade()).isEqualTo("C");
    }

    // [상황] 출고는 전체 검사, 수령은 빠른 검사, 반납은 전체 검사로 찍었다.
    //        반납 전체 검사의 위치 비교는 출고와 견주므로, 배송 중 생긴 찍힘(수령 때 이미 있던 것)을 새 흠집으로 본다.
    // [기대] 바로 앞 시점(수령)이 빠른 검사이므로 출고와 위치 비교하지 않고 수령과 개수로 견준다.
    //        수령 때 이미 있던 찍힘은 빼서 차감 0원이고, "앞 시점과 같은 수만큼의 흠집 1개는 빼고 계산"이라고 적는다
    //        (고객이 배송 중 손상까지 물어내지 않게).
    @Test
    @DisplayName("반납이 전체 검사여도 앞 시점(수령)이 빠른 검사면 출고와 위치 비교하지 않고 수령과 수로 견줘 배송 중 손상을 빼다")
    void fullReturnAfterQuickReceiptComparesWithReceipt() {
        CaptureSet rcv = completedQuickSet("check-00000000000002");
        CaptureSet ret = completedSet("check-00000000000003", CaptureProfile.FULL);
        graded(ret, Grade.B, 2);
        GradeResult prev = new GradeResult(rcv.getCheckpointId());
        prev.recordGrade(rcv, Grade.B, "grade-rules-v1", List.of(), List.of());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(anyString(),
                anyString(), any()))
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
                2_057_693L);

        SettlementView view = svc.get(ADMIN, ret.getCheckpointId());

        assertThat(view.lines()).isEmpty();
        assertThat(view.total()).isZero();
        assertThat(view.notes()).contains("앞 시점과 같은 수만큼의 흠집 1개는 빼고 계산");
    }

    // [상황] 수령 시점의 정산서를 연다.
    // [기대] 수령 때 흠집은 배송 중 손상이라 정산 대상 아님(NOT_APPLICABLE), 0원이다.
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

    // [상황] 반납 세트는 올라왔지만 아직 검수·등급이 나지 않았다.
    // [기대] "기다리는 중"(PENDING)으로 차감 항목 없이 돌려준다.
    @Test
    @DisplayName("검수·등급 전이면 PENDING으로 금액 없이 돌려준다")
    void pendingBeforeGrade() {
        CaptureSet ret = completedQuickSet("check-00000000000003");
        when(grades.findByCheckpointId(ret.getCheckpointId())).thenReturn(Optional.empty());
        CaptureSetRepository sets = mock(CaptureSetRepository.class);
        when(sets.findFirstByCheckpointIdOrderByIdDesc(ret.getCheckpointId())).thenReturn(Optional.of(ret));
        GradingService grading = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, mock(GradeEventPublisher.class));
        SettlementView view = new SettlementService(sets, grades, defects, grading, 2_057_693L)
                .get(ADMIN, ret.getCheckpointId());
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.lines()).isEmpty();
    }

    // 테스트 도구: 그 세트에 등급을 기록하고, 같은 렌탈에서 앞서 등급 난 시점 수(earlier, 시점 종류를 정함)를 정해 둔다.
    private void graded(CaptureSet set, Grade grade, long earlier) {
        GradeResult result = new GradeResult(set.getCheckpointId());
        result.recordGrade(set, grade, "grade-rules-v1", List.of(), List.of());
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(result));
        when(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(anyString(),
                eq(set.getCheckpointId()), any())).thenReturn(earlier);
    }

    // 테스트 도구: 다 올린 부스 빠른 검사 세트를 만든다.
    private static CaptureSet completedQuickSet(String checkpointId) {
        return completedSet(checkpointId, CaptureProfile.QUICK);
    }

    // 테스트 도구: 다 올린 부스 세트를 검사 방식을 정해 만든다.
    private static CaptureSet completedSet(String checkpointId, CaptureProfile profile) {
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, CaptureSource.BOOTH,
                "booth-1", null, profile, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        return set;
    }

    // 테스트 도구: 세트에서 그 면·카메라의 L1 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera) {
        return photo(set, face, camera, Light.L1);
    }

    // 테스트 도구: 세트에서 그 면·카메라·조명의 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera, Light light) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == camera && p.getLight() == light).findFirst()
                .orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
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
