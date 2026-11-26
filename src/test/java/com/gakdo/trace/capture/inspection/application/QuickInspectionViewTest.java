// 이 파일이 확인하는 것: 빠른 검사 결과 조회에서 사진별 흠집(photoDefects)을 어디까지 보여 주는지.
// 사진 목록(/media)과 같은 범위, 즉 완료 때 실제로 올라간 사진·건너뛰지 않은 면의 흠집만 싣는다.

package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/** 빠른 검사 결과 조회: photoDefects는 /media와 같은 범위(완료 때 올라간 사진, 건너뛰지 않은 면)만 싣는다. */
class QuickInspectionViewTest {

    // 사진 SHA-256 자리에 넣을 64자 가짜 값
    private static final String SHA = "c".repeat(64);
    // 테스트용 시점 ID
    private static final String CHECKPOINT = "check-00000000000001";
    // 흠집 테두리(네 꼭짓점 좌표) 견본
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    // 요청한 사람 = 관리자
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    // [상황] 부스 빠른 검사에서 바닥(BOT) 사진은 올라갔고 윗면(TOP)은 "업로드 실패"로 건너뛴 채 완료됐다.
    //        그런데 탐지 결과는 TOP·BOT 둘 다에 흠집이 하나씩 먼저 와 있었다.
    // [기대] 결과 화면의 사진별 흠집에는 BOT만 남고 건너뛴 TOP 흠집은 빠진다(사진도 없는 면에 흠집을 보여 주지 않음).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("건너뛴 면에 먼저 온 탐지는 빠른 검사 결과의 photoDefects에서 빠진다")
    void skippedFaceDefectsAreHidden() {
        // 준비: 부스 빠른 검사 세트(사진 12장 목록)를 연다
        CaptureSet set = CaptureSet.open("rental-000000000000001", CHECKPOINT, CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.QUICK, specs());
        // 준비: 실제로 올라간 사진 ID를 담을 묶음을 만든다
        Set<String> present = new HashSet<>();
        // 준비: 바닥(BOT) 사진만 올라갔다고 묶음에 넣는다
        set.getPhotos().stream().filter(p -> p.getFace() == Face.BOT).forEach(p -> present.add(p.getPublicId()));
        // 준비: 윗면(TOP)은 "업로드 실패"로 건너뛰고 세트를 완료한다
        set.complete(present, Map.of(Face.TOP, "업로드 실패"), "key-1", SHA);

        // (도구) mock(클래스) = 진짜 DB 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값을 돌려준다
        // 준비: 흠집 저장소를 가짜로 만든다
        DefectRepository defects = mock(DefectRepository.class);
        // 준비: 등급 결과 저장소를 가짜로 만든다
        GradeResultRepository grades = mock(GradeResultRepository.class);
        // 준비: 촬영 세트 저장소를 가짜로 만든다
        CaptureSetRepository sets = mock(CaptureSetRepository.class);
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 이 시점의 등급 결과를 물으면 "아직 없음"을 돌려주게 한다
        when(grades.findByCheckpointId(CHECKPOINT)).thenReturn(Optional.empty());
        // 준비: 이 시점의 최신 세트를 물으면 위 세트를 돌려주게 한다
        when(sets.findFirstByCheckpointIdOrderByIdDesc(CHECKPOINT)).thenReturn(Optional.of(set));
        // 준비: 그 세트의 흠집을 물으면 TOP·BOT에 긁힘 하나씩(신뢰도 0.9)을 돌려주게 한다 (TOP 흠집은 건너뛴 면에 먼저 온 탐지)
        when(defects.findBySet(set)).thenReturn(List.of(
                Defect.fromBooth(photo(set, Face.TOP), DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(photo(set, Face.BOT), DefectClass.SCRATCH, BOX, 0.9, "v")));

        // 실행: 가짜 저장소들로 검사 조회 서비스를 만들어 관리자로 이 시점 결과를 조회한다
        var view = new InspectionQueryService(sets, defects, mock(DefectMatchRepository.class), grades,
                mock(FaceAlignmentRepository.class))
                .get(ADMIN, CHECKPOINT);

        // (도구) assertThat(실제값).isEmpty() = 비어 있어야 한다는 검사
        // 확인: 등급이 아직 없으니 확정 흠집 목록은 비어 있어야 한다
        assertThat(view.defects()).isEmpty();
        // 확인: 사진별 흠집에서 면만 뽑아 보면 (extracting = 각 항목에서 값 뽑기)
        assertThat(view.photoDefects()).extracting(InspectionQueryService.PhotoDefectView::face)
                // BOT 하나뿐이어야 한다 (건너뛴 TOP 흠집은 빠짐)
                .containsExactly("BOT");
    }

    // 테스트 도구: 세트에서 그 면의 CAM1·L1 사진 하나를 찾는다.
    private static Photo photo(CaptureSet set, Face face) {
        // 세트 사진 중 면이 같고 CAM1·L1인 첫 사진을 찾아 돌려준다 (없으면 실패)
        return set.getPhotos().stream().filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1
                && p.getLight() == Light.L1).findFirst().orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        // 사진 목록을 담을 빈 목록
        List<PhotoSpec> specs = new ArrayList<>();
        // 찍은 시각을 고정한다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
        // 바닥·윗면 두 면마다
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            // 조명 L1~L3 세 가지마다
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                // CAM1 사진 한 장을 넣고
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                // CAM2 사진 한 장을 넣는다 (2면 × 3조명 × 2카메라 = 12장)
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        // 만든 12장 목록을 돌려준다
        return specs;
    }
}
