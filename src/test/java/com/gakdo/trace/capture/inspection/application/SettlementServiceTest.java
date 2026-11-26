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

    // 가짜 사진 해시값(64자리 "b")
    private static final String SHA = "b".repeat(64);
    // 사진 위 흠집 모양: 왼쪽 위 (10,10)~(50,30) 네모
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    // BOX와 멀리 떨어진 자리 네모. 다른 흠집으로 보이게 하려고
    private static final List<List<Double>> FAR = List.of(List.of(900.0, 600.0), List.of(940.0, 600.0),
            List.of(940.0, 620.0), List.of(900.0, 620.0));
    // 조회하는 사람: 관리자
    private static final Caller ADMIN = new Caller("ADMIN", null, null, null);

    // 가짜 흠집 저장소
    private DefectRepository defects;
    // 가짜 등급 결과 저장소
    private GradeResultRepository grades;
    // 시험할 대상: 반납 정산 서비스
    private SettlementService service;

    // 테스트 도구: 가짜 흠집·등급 저장소로 정산 서비스를 만든다.
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 흠집 저장소를 가짜로 만든다
        defects = mock(DefectRepository.class);
        // 준비: 등급 결과 저장소를 가짜로 만든다
        grades = mock(GradeResultRepository.class);
        // 준비: 진짜 등급 서비스를 가짜 저장소들로 만든다 (흠집 묶기 계산은 진짜로 하려고)
        GradingService grading = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, mock(GradeEventPublisher.class));
        // 준비: 기기 기준가 2,057,693원으로 정산 서비스를 만든다
        service = new SettlementService(mock(CaptureSetRepository.class), grades, defects, grading, 2_057_693L);
    }

    // [상황] 앞 시점(수령)에는 윗면 긁힘 1개, 반납에는 같은 긁힘 + 떨어진 자리 찍힘 1개가 있다. 반납 등급은 C다.
    // [기대] 정산서는 "준비됨"(READY), 시점은 반납(RET)이고, 새로 늘어난 찍힘 하나만 31,000원으로 매긴다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("반납 빠른 검사는 앞 시점보다 늘어난 흠집만 규칙표 금액으로 매긴다")
    void returnChargesOnlyIncrease() {
        // 준비: 앞 시점(수령) 빠른 검사 세트를 만든다
        CaptureSet before = completedQuickSet("check-00000000000002");
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 앞 시점 흠집을 물으면 윗면 BOX 자리 긁힘 하나를 돌려주게 한다
        when(defects.findBySet(before)).thenReturn(List.of(
                Defect.fromBooth(photo(before, Face.TOP, Camera.CAM1), DefectClass.SCRATCH, BOX, 0.9, "v")));
        // 준비: 반납 빠른 검사 세트를 만든다
        CaptureSet ret = completedQuickSet("check-00000000000003");
        // 준비: 반납 흠집을 물으면 같은 자리 긁힘 + 먼 자리 찍힘을 돌려주게 한다
        when(defects.findBySet(ret)).thenReturn(List.of(
                Defect.fromBooth(photo(ret, Face.TOP, Camera.CAM1), DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(photo(ret, Face.TOP, Camera.CAM1), DefectClass.DENT, FAR, 0.8, "v")));
        // 준비: 반납 세트에 등급 C를 기록하고, 앞서 등급 난 시점이 2개(→ 반납)라고 정한다
        graded(ret, Grade.C, 2);
        // 준비: 앞 시점의 등급 결과를 만들고
        GradeResult prev = new GradeResult(before.getCheckpointId());
        // 등급 B로 기록한다
        prev.recordGrade(before, Grade.B, "grade-rules-v1", List.of(), List.of());
        // (도구) anyString() = 아무 글자, any() = 아무 값
        // 준비: "같은 렌탈에서 이 시점 바로 앞 등급"을 물으면 앞 시점 등급을 돌려주게 한다
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(anyString(),
                anyString(), any()))
                .thenReturn(Optional.of(prev));

        // 실행: 관리자로 반납 시점의 정산서를 연다
        SettlementView view = service.get(ADMIN, ret.getCheckpointId());

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 상태는 준비됨(READY)이어야 한다
        assertThat(view.status()).isEqualTo("READY");
        // 확인: 시점 종류는 반납(RET)이어야 한다
        assertThat(view.checkpointType()).isEqualTo("RET");
        // (도구) extracting(칸).containsExactly(...) = 목록 각 항목에서 그 칸만 뽑아, 정확히 이 값들이 이 순서로 있어야 한다는 검사
        // 확인: 차감 항목은 새로 늘어난 찍힘 하나뿐이어야 한다
        assertThat(view.lines()).extracting(SettlementService.LineView::defectClass).containsExactly("dent");
        // 확인: 합계는 31,000원이어야 한다
        assertThat(view.total()).isEqualTo(31_000);
        // 확인: 반납 등급 C도 함께 보여야 한다
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
        // 준비: 수령 시점 빠른 검사 세트를 만든다
        CaptureSet rcv = completedQuickSet("check-00000000000002");
        // 준비: 반납 시점 전체 검사 세트를 만든다
        CaptureSet ret = completedSet("check-00000000000003", CaptureProfile.FULL);
        // 준비: 반납에 등급 B, 앞서 등급 난 시점 2개(→ 반납)로 정한다
        graded(ret, Grade.B, 2);
        // 준비: 수령 시점 등급 결과를 만들고
        GradeResult prev = new GradeResult(rcv.getCheckpointId());
        // 등급 B로 기록한다
        prev.recordGrade(rcv, Grade.B, "grade-rules-v1", List.of(), List.of());
        // 준비: 바로 앞 시점 등급을 물으면 수령 등급을 돌려주게 한다
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(anyString(),
                anyString(), any()))
                .thenReturn(Optional.of(prev));
        // 출고는 전체 검사였다(예전 코드는 이것만 보고 위치 비교를 썼다)
        // 준비: 출고 시점 전체 검사 세트를 만든다
        CaptureSet out = completedSet("check-00000000000001", CaptureProfile.FULL);
        // 준비: 출고 등급 결과를 만들고
        GradeResult outGrade = new GradeResult(out.getCheckpointId());
        // 등급 A로 기록한다
        outGrade.recordGrade(out, Grade.A, "grade-rules-v1", List.of(), List.of());
        // 준비: "같은 렌탈에서 같은 검사 방식의 가장 최근 등급"을 물으면 출고 등급을 돌려주게 한다 (함정: 이걸 쓰면 안 됨)
        when(grades.findFirstByCaptureSetRentalIdAndCaptureSetProfileAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(),
                any(), anyString())).thenReturn(Optional.of(outGrade));
        // 반납 전체 검사의 위치 비교는 출고와 견줘 배송 중 생긴 찍힘을 NEW로 본다. 수령(빠른 검사)에 이미 같은 찍힘이 있다
        // 준비: 이번엔 등급 서비스를 가짜로 만든다 (묶인 흠집을 직접 정해 주려고)
        GradingService grading = mock(GradingService.class);
        // 준비: 반납의 묶인 흠집 = 윗면 찍힘 6mm, 신규(출고와 위치 비교한 결과)
        when(grading.gradedGroups(ret)).thenReturn(List.of(
                new DefectGroups.Group(Face.TOP, DefectClass.DENT, 6.0, true, List.of("ret-dent"))));
        // 준비: 수령의 묶인 흠집 = 같은 윗면 찍힘 6mm (빠른 검사라 신규 여부 없음)
        when(grading.gradedGroups(rcv)).thenReturn(List.of(
                new DefectGroups.Group(Face.TOP, DefectClass.DENT, 6.0, null, List.of("rcv-dent"))));
        // 준비: 원본 흠집 목록은 어느 세트든 빈 목록으로 답하게 한다
        when(defects.findBySet(any())).thenReturn(List.of());
        // 준비: 가짜 등급 서비스로 정산 서비스를 새로 만든다
        SettlementService svc = new SettlementService(mock(CaptureSetRepository.class), grades, defects, grading,
                2_057_693L);

        // 실행: 반납 시점의 정산서를 연다
        SettlementView view = svc.get(ADMIN, ret.getCheckpointId());

        // (도구) isEmpty() = 목록이 비어 있어야 한다는 검사
        // 확인: 차감 항목이 없어야 한다 (수령 때 이미 있던 찍힘이므로)
        assertThat(view.lines()).isEmpty();
        // (도구) isZero() = 값이 0이어야 한다는 검사
        // 확인: 합계가 0원이어야 한다
        assertThat(view.total()).isZero();
        // (도구) contains(...) = 목록에 이 값이 들어 있어야 한다는 검사
        // 확인: "앞 시점과 같은 수만큼의 흠집 1개는 빼고 계산" 안내가 있어야 한다
        assertThat(view.notes()).contains("앞 시점과 같은 수만큼의 흠집 1개는 빼고 계산");
    }

    // [상황] 수령 시점의 정산서를 연다.
    // [기대] 수령 때 흠집은 배송 중 손상이라 정산 대상 아님(NOT_APPLICABLE), 0원이다.
    @Test
    @DisplayName("수령 시점은 배송 중 손상이라 정산하지 않는다")
    void receiptIsNotApplicable() {
        // 준비: 수령 시점 빠른 검사 세트를 만든다
        CaptureSet rcv = completedQuickSet("check-00000000000002");
        // 준비: 등급 B, 앞서 등급 난 시점 1개(→ 수령)로 정한다
        graded(rcv, Grade.B, 1);
        // 실행: 수령 시점의 정산서를 연다
        SettlementView view = service.get(ADMIN, rcv.getCheckpointId());
        // 확인: 상태는 정산 대상 아님(NOT_APPLICABLE)이어야 한다
        assertThat(view.status()).isEqualTo("NOT_APPLICABLE");
        // 확인: 시점 종류는 수령(RCV)이어야 한다
        assertThat(view.checkpointType()).isEqualTo("RCV");
        // 확인: 합계는 0원이어야 한다
        assertThat(view.total()).isZero();
    }

    // [상황] 반납 세트는 올라왔지만 아직 검수·등급이 나지 않았다.
    // [기대] "기다리는 중"(PENDING)으로 차감 항목 없이 돌려준다.
    @Test
    @DisplayName("검수·등급 전이면 PENDING으로 금액 없이 돌려준다")
    void pendingBeforeGrade() {
        // 준비: 반납 빠른 검사 세트를 만든다
        CaptureSet ret = completedQuickSet("check-00000000000003");
        // 준비: 이 시점의 등급을 물으면 아직 없다(빈 값)고 답하게 한다
        when(grades.findByCheckpointId(ret.getCheckpointId())).thenReturn(Optional.empty());
        // 준비: 세트 저장소를 가짜로 만든다
        CaptureSetRepository sets = mock(CaptureSetRepository.class);
        // 준비: 이 시점의 최근 세트를 물으면 반납 세트를 돌려주게 한다
        when(sets.findFirstByCheckpointIdOrderByIdDesc(ret.getCheckpointId())).thenReturn(Optional.of(ret));
        // 준비: 진짜 등급 서비스를 가짜 저장소들로 만든다
        GradingService grading = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grades, mock(GradeEventPublisher.class));
        // 실행: 이 저장소들로 정산 서비스를 만들어 반납 시점의 정산서를 연다
        SettlementView view = new SettlementService(sets, grades, defects, grading, 2_057_693L)
                .get(ADMIN, ret.getCheckpointId());
        // 확인: 상태는 기다리는 중(PENDING)이어야 한다
        assertThat(view.status()).isEqualTo("PENDING");
        // 확인: 차감 항목이 없어야 한다
        assertThat(view.lines()).isEmpty();
    }

    // 테스트 도구: 그 세트에 등급을 기록하고, 같은 렌탈에서 앞서 등급 난 시점 수(earlier, 시점 종류를 정함)를 정해 둔다.
    private void graded(CaptureSet set, Grade grade, long earlier) {
        // 이 시점의 등급 결과를 만들고
        GradeResult result = new GradeResult(set.getCheckpointId());
        // 받은 등급으로 기록한다
        result.recordGrade(set, grade, "grade-rules-v1", List.of(), List.of());
        // 이 시점의 등급을 물으면 위 결과를 돌려주게 한다
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(result));
        // (도구) eq(값) = 정확히 이 값일 때만. any()와 섞어 쓸 때 쓴다
        // "이 시점보다 앞서 등급 난 시점 수"를 물으면 earlier를 돌려주게 한다
        when(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(anyString(),
                eq(set.getCheckpointId()), any())).thenReturn(earlier);
    }

    // 테스트 도구: 다 올린 부스 빠른 검사 세트를 만든다.
    private static CaptureSet completedQuickSet(String checkpointId) {
        // 빠른 검사 방식으로 다 올린 세트를 만들어 돌려준다
        return completedSet(checkpointId, CaptureProfile.QUICK);
    }

    // 테스트 도구: 다 올린 부스 세트를 검사 방식을 정해 만든다.
    private static CaptureSet completedSet(String checkpointId, CaptureProfile profile) {
        // 부스 세트를 받은 방식과 12장 목록으로 연다
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, CaptureSource.BOOTH,
                "booth-1", null, profile, specs());
        // "올라온 사진" ID를 모을 빈 집합
        Set<String> present = new HashSet<>();
        // 모든 사진을 "올라옴"에 넣는다
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        // 세트를 완료한다 (요청 키는 세트마다 달라지게)
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        // 완료된 세트를 돌려준다
        return set;
    }

    // 테스트 도구: 세트에서 그 면·카메라의 L1 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera) {
        // 조명 L1로 아래 도우미를 부른다
        return photo(set, face, camera, Light.L1);
    }

    // 테스트 도구: 세트에서 그 면·카메라·조명의 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face, Camera camera, Light light) {
        // 세트 사진 중 그 면·카메라·조명인 첫 사진을 돌려준다 (없으면 예외)
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == camera && p.getLight() == light).findFirst()
                .orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 모든 사진에 같은 촬영 시각을 쓴다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
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
