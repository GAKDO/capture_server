// 이 파일이 확인하는 것: 전체 검사 결과 조회(11/9). 관리자 화면에서 AI가 무엇을 보고 판정했는지 알 수 있게,
// 원본 사진 위 흠집(photoDefects)과 "판정 불가 면"(사진은 있지만 면을 펼치지 못했거나 건너뛴 면)과 그 이유를 함께 보여 준다.

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
import org.springframework.test.util.ReflectionTestUtils;

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
import com.gakdo.trace.capture.inspection.domain.AlignmentMethod;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.FaceAlignment;
import com.gakdo.trace.capture.inspection.domain.Homography;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/** 전체 검사 결과 조회(11/9): 원본 px 흠집과 판정 불가 면을 함께 보여 AI가 무엇을 보고 판정했는지 알 수 있게 한다. */
class FullInspectionViewTest {

    // 가짜 해시값(64자리 "d"). 사진·완료 요청에 쓴다
    private static final String SHA = "d".repeat(64);
    // 조회할 시점(체크포인트) ID
    private static final String CHECKPOINT = "check-00000000000009";
    // 사진 위 흠집 모양: (10,10)~(50,30) 네모의 네 꼭짓점
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    // 변환 행렬(3×3) 중 "아무것도 바꾸지 않는" 단위 행렬. 사진 좌표 = 펼친 면 좌표로 단순하게 하려고
    private static final List<List<Double>> IDENTITY = List.of(List.of(1.0, 0.0, 0.0), List.of(0.0, 1.0, 0.0),
            List.of(0.0, 0.0, 1.0));
    // 조회하는 사람: 관리자
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    // [상황] 부스 전체 검사 세트를 다 올렸다. 키보드(KB)에 찍힘, 앞 옆면(SF)에 긁힘이 잡혔다.
    //        다른 면은 면 맞추기(정합)에 성공했지만, SF는 recon-worker가 "면이 사진 밖으로 나감(두 변 이상 잘림)"으로 실패했다.
    // [기대] 판정에 쓰인 흠집은 KB 하나뿐이지만, 사진 위 흠집은 KB와 SF 둘 다 보인다(같은 흠집은 같은 ID).
    //        판정 불가 면으로 SF가 하나 오고, 이유에 카메라(CAM1)와 실패 사유가 담긴다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("전체 검사도 정합에 실패한 옆면의 부스 탐지까지 photoDefects로 보이고, 판정 불가 면과 worker 실패 사유가 함께 온다")
    void fullSetShowsPhotoDefectsAndUnjudgeableFaces() {
        // 준비: 부스 전체 검사(FULL) 세트를 줄인 사진 목록으로 연다
        CaptureSet set = CaptureSet.open("rental-000000000000009", CHECKPOINT, CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.FULL, specs());
        // 준비: 사진마다 붙일 DB 번호를 1부터 센다
        long id = 1;
        // 준비: "올라온 사진" ID를 모을 빈 집합을 만든다
        Set<String> present = new HashSet<>();
        // 세트의 사진 하나하나에 대해
        for (Photo p : set.getPhotos()) {
            // (도구) ReflectionTestUtils.setField(객체, "칸", 값) = 원래 DB가 채우는 숨은 칸을 테스트에서 직접 채우는 도구
            // DB 없이도 사진에 번호가 있게 id를 채운다
            ReflectionTestUtils.setField(p, "id", id++);
            // 이 사진을 "올라옴"에 넣는다
            present.add(p.getPublicId());
        }
        // 준비: 모든 사진이 올라왔고 건너뛴 면은 없다고 세트를 완료한다 (요청 키 key-9, 요청 해시 SHA)
        set.complete(present, Map.of(), "key-9", SHA);

        // 준비: 키보드(KB) CAM1·L1 사진에서 부스가 찾은 찍힘을 만든다 (확신도 0.4, 모델 v004)
        Defect kb = Defect.fromBooth(photo(set, Face.KB), DefectClass.DENT, BOX, 0.4, "v004");
        // 준비: 앞 옆면(SF) 사진에서 부스가 찾은 긁힘을 만든다 (확신도 0.7)
        Defect side = Defect.fromBooth(photo(set, Face.SF), DefectClass.SCRATCH, BOX, 0.7, "v004");
        // 옆면을 뺀 모든 사진은 정합 성공, SF는 worker가 실패 사유를 보냈다
        // 준비: 정합(면 맞추기)에 성공한 결과를 모을 빈 목록
        List<FaceAlignment> aligned = new ArrayList<>();
        // 사진 하나하나에 대해
        for (Photo p : set.getPhotos()) {
            // 앞 옆면(SF) 사진이면
            if (p.getFace() == Face.SF) {
                // worker가 보낸 실패 사유를 사진에 기록하고
                p.markAlignFailed("면이 사진 밖으로 나감(두 변 이상 잘림)", OffsetDateTime.now());
                // 정합 성공 목록에는 넣지 않고 다음 사진으로 넘어간다
                continue;
            }
            // 나머지 사진은 윤곽(CONTOUR) 방식, 단위 행렬, 점수 1.0으로 정합 성공 결과를 만든다
            aligned.add(new FaceAlignment(p, AlignmentMethod.CONTOUR, IDENTITY, 1.0, "rectified/" + p.getPublicId()));
        }
        // 준비: 키보드 찍힘만 펼친 면 위에 위치를 정한다 (옆면 긁힘은 정합 실패라 위치 없음)
        kb.place(Homography.of(IDENTITY));
        // 준비: 세트를 "검사 끝남" 상태로 바꾼다
        set.markInspected();

        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 흠집 저장소를 가짜로 만든다
        DefectRepository defects = mock(DefectRepository.class);
        // 준비: 등급 결과 저장소를 가짜로 만든다
        GradeResultRepository grades = mock(GradeResultRepository.class);
        // 준비: 세트 저장소를 가짜로 만든다
        CaptureSetRepository sets = mock(CaptureSetRepository.class);
        // 준비: 정합 결과 저장소를 가짜로 만든다
        FaceAlignmentRepository alignments = mock(FaceAlignmentRepository.class);
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 이 시점의 등급 결과는 아직 없다(빈 값)고 답하게 한다
        when(grades.findByCheckpointId(CHECKPOINT)).thenReturn(Optional.empty());
        // 준비: 이 시점의 가장 최근 세트를 물으면 위 세트를 돌려주게 한다
        when(sets.findFirstByCheckpointIdOrderByIdDesc(CHECKPOINT)).thenReturn(Optional.of(set));
        // 준비: 세트의 흠집을 물으면 키보드 찍힘과 옆면 긁힘을 돌려주게 한다
        when(defects.findBySet(set)).thenReturn(List.of(kb, side));
        // 준비: 세트의 정합 결과를 물으면 성공 목록을 돌려주게 한다
        when(alignments.findBySet(set)).thenReturn(aligned);

        // 실행: 가짜들로 검사 조회 서비스를 만들어 관리자로 이 시점의 검사 결과를 조회한다
        var view = new InspectionQueryService(sets, defects, mock(DefectMatchRepository.class), grades, alignments)
                .get(ADMIN, CHECKPOINT);

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 검사 종류는 전체 검사(FULL)여야 한다
        assertThat(view.profile()).isEqualTo("FULL");
        // (도구) extracting(칸).containsExactly(...) = 목록 각 항목에서 그 칸만 뽑아, 정확히 이 값들이 이 순서로 있어야 한다는 검사
        // 확인: 판정에 쓰인 흠집은 위치가 정해진 키보드(KB) 하나뿐이어야 한다
        assertThat(view.defects()).extracting(InspectionQueryService.DefectView::face).containsExactly("KB");
        // 확인: 사진 위 흠집은 키보드와 앞 옆면(SF) 둘 다 보여야 한다 (AI가 본 것을 전부 보이려고)
        assertThat(view.photoDefects()).extracting(InspectionQueryService.PhotoDefectView::face)
                .containsExactly("KB", "SF");
        // 확인: 사진 위 키보드 흠집과 판정 흠집은 같은 ID여야 한다 (화면에서 서로 이어 보이려고)
        assertThat(view.photoDefects().get(0).defectId()).isEqualTo(view.defects().get(0).defectId());
        // (도구) singleElement().satisfies(u -> ...) = 목록에 딱 하나만 있어야 하고, 그 하나가 안의 검사를 통과해야 한다는 뜻
        // 확인: 판정 불가 면은 하나만 있어야 하고
        assertThat(view.unjudgeableFaces()).singleElement().satisfies(u -> {
            // 확인: 그 면은 앞 옆면(SF)이어야 한다
            assertThat(u.face()).isEqualTo("SF");
            // (도구) contains(글자) = 문장에 그 글자가 들어 있어야 한다는 검사
            // 확인: 이유에 카메라(CAM1)와 worker 실패 사유가 함께 담겨야 한다
            assertThat(u.reason()).contains("CAM1").contains("두 변 이상 잘림");
        });
    }

    // [상황] 전체 검사 세트를 완료했고 흰 화면(SCR1)은 "스킵"으로 건너뛰었다. 아직 정합 결과는 오지 않았다.
    // [기대] 정합 실패는 따지지 않고, 판정 불가 면은 건너뛴 SCR1("건너뜀: 스킵") 하나만 보인다.
    @Test
    @DisplayName("정합 결과가 오기 전의 전체 검사는 정합 실패를 따지지 않고 건너뛴 면만 판정 불가로 보인다")
    void beforeInspectionOnlySkippedFaces() {
        // 준비: 부스 전체 검사 세트를 연다
        CaptureSet set = CaptureSet.open("rental-000000000000009", CHECKPOINT, CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.FULL, specs());
        // 준비: "올라온 사진" ID를 모을 빈 집합
        Set<String> present = new HashSet<>();
        // 준비: 모든 사진을 "올라옴"에 넣는다
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        // 준비: 흰 화면(SCR1)은 "스킵" 이유로 건너뛰었다고 하고 세트를 완료한다
        set.complete(present, Map.of(Face.SCR1, "스킵"), "key-9", SHA);
        // 준비: 정합 결과 저장소를 가짜로 만든다 (아무 결과도 정해 두지 않음 = 아직 안 옴)
        FaceAlignmentRepository alignments = mock(FaceAlignmentRepository.class);

        // 준비: 가짜 저장소들로 검사 조회 서비스를 만든다
        var service = new InspectionQueryService(mock(CaptureSetRepository.class), mock(DefectRepository.class),
                mock(DefectMatchRepository.class), mock(GradeResultRepository.class), alignments);

        // (도구) containsExactly(...) = 정확히 이 값들만 이 순서로 있어야 한다는 검사
        // 실행+확인: 판정 불가 면은 건너뛴 SCR1("건너뜀: 스킵") 하나뿐이어야 한다
        assertThat(service.unjudgeableFaces(set)).containsExactly(
                new InspectionQueryService.UnjudgeableFace("SCR1", "건너뜀: 스킵"));
    }

    // 테스트 도구: 세트에서 그 면의 CAM1·L1 사진 하나를 찾는다.
    private static Photo photo(CaptureSet set, Face face) {
        // 세트 사진 중 그 면·CAM1·L1인 첫 사진을 돌려준다 (없으면 예외)
        return set.getPhotos().stream().filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1
                && p.getLight() == Light.L1).findFirst().orElseThrow();
    }

    // 테스트 도구: 전체 검사 사진 목록을 줄여서 만든다(바닥·윗면·키보드는 두 카메라, 옆면 4개와 화면은 CAM1 한 장씩).
    private static List<PhotoSpec> specs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 모든 사진에 같은 촬영 시각을 쓴다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-09T14:00:00+09:00");
        // 바닥·윗면·키보드마다
        for (Face face : List.of(Face.BOT, Face.TOP, Face.KB)) {
            // CAM1 사진 한 장
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM1, "jpg", SHA, at));
            // CAM2 사진 한 장
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM2, "jpg", SHA, at));
        }
        // 옆면 4개(앞·뒤·왼·오른)와 화면(SCR0)마다
        for (Face face : List.of(Face.SF, Face.SB, Face.SL, Face.SR, Face.SCR0)) {
            // CAM1 사진 한 장씩
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM1, "jpg", SHA, at));
        }
        // 완성된 사진 목록을 돌려준다
        return specs;
    }
}
