// 이 파일이 확인하는 것: 부스가 보낸 빠른 검사 탐지 결과를 받을 때, TOP·BOT 사진 12장을 전부 분석한 결과인지 확인하는 규칙(contracts #20 리뷰).
// 일부 사진만 분석한 결과로 등급이 나면 흠집을 놓친 채 "문제 없음"이 될 수 있기 때문이다.

package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.application.CaptureSetService.PhotoRef;
import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;

/** 빠른 검사 탐지 요청은 TOP·BOT 사진 전부의 분석 결과여야 한다(contracts #20 리뷰). Docker 없이 돈다. */
class QuickDetectionTest {

    // 가짜 사진 해시값(64자리 "c"). 사진 목록을 만들 때 쓴다
    private static final String SHA = "c".repeat(64);
    // 요청한 쪽: 부스 booth-1 (탐지 결과를 보내는 주체)
    private static final Caller BOOTH = new Caller("BOOTH", "booth-1", null, null);

    // 가짜 촬영 세트 저장소
    private CaptureSetRepository sets;
    // 가짜 흠집 저장소
    private DefectRepository defects;
    // 가짜 등급 서비스
    private GradingService grading;
    // 시험할 대상: 탐지 결과 저장 서비스
    private DetectionService service;
    // 테스트에 쓸 빠른 검사 세트
    private CaptureSet set;

    // 테스트 도구: 가짜 저장소로 탐지 결과 저장 서비스를 만들고, 부스 빠른 검사 세트(12장) 하나를 연다.
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 세트 저장소를 가짜로 만든다
        sets = mock(CaptureSetRepository.class);
        // 준비: 흠집 저장소를 가짜로 만든다
        defects = mock(DefectRepository.class);
        // 준비: 등급 서비스를 가짜로 만든다 (등급 판정으로 넘겼는지 나중에 확인하려고)
        grading = mock(GradingService.class);
        // 준비: 가짜들로 시험 대상 서비스를 만든다 (흠집 짝·면 정렬 저장소는 이 테스트에서 안 봐서 바로 가짜로 넣는다)
        service = new DetectionService(sets, defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grading);
        // 준비: 부스 빠른 검사(QUICK) 세트를 사진 12장 목록으로 연다
        set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.QUICK, specs());
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. any() = 아무 값
        // 준비: 어떤 세트 ID로 잠가 읽어도 위 세트를 돌려주게 한다
        when(sets.findForUpdate(any())).thenReturn(Optional.of(set));
    }

    // [상황] 부스가 "분석한 사진 목록(analyzedPhotos)" 없이 탐지 결과를 보낸다.
    // [기대] 400(analyzedPhotos 칸 오류)으로 거절하고, 흠집 저장도 등급 판정도 하지 않는다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("빠른 검사 탐지 요청에 analyzedPhotos가 없으면 400(analyzedPhotos)이고 저장·등급을 하지 않는다")
    void analyzedRequired() {
        // (도구) assertThatThrownBy(() -> 코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 흠집 0개, 분석한 사진 목록 null로 저장을 부르면 예외가 나야 한다
        assertThatThrownBy(() -> service.save(BOOTH, set.getPublicId(), "v", List.of(), null))
                // (도구) isInstanceOfSatisfying(종류, e -> ...) = 예외가 그 종류이고, 안의 검사도 통과해야 한다는 뜻
                // 확인: 예외는 우리 도메인 예외여야 하고, 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
                    // 확인: 오류 코드는 입력값 검증 실패(400)여야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    // (도구) extracting(칸) = 목록의 각 항목에서 그 칸만 뽑아 검사한다. containsExactly = 정확히 이 값들만 이 순서로
                    // 확인: 잘못된 칸은 analyzedPhotos 하나뿐이어야 한다 (어느 칸이 문제인지 알려 주려고)
                    assertThat(e.errors()).extracting(DomainException.FieldError::field)
                            .containsExactly("analyzedPhotos");
                });
        // (도구) verify(가짜, never()).메서드(...) = 그 메서드가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 기존 흠집을 지우는(새로 저장하기 전 단계) 일이 없어야 한다
        verify(defects, never()).deleteBySet(any());
        // 확인: 등급 판정으로도 넘어가지 않았어야 한다
        verify(grading, never()).gradeIfReady(any());
    }

    // [상황] 12장 중 한 장이 분석 목록에서 빠졌다.
    // [기대] 400으로 거절해 일부만 분석한 결과로 등급이 나지 않는다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("TOP·BOT 사진 중 하나라도 분석 목록에서 빠지면 400이라 일부만 분석한 결과로 등급이 나지 않는다")
    void partialRejected() {
        // 준비: 12장 전부를 담은 분석 목록을 복사해 만든다
        List<PhotoRef> analyzed = new ArrayList<>(all());
        // 준비: 그중 첫 장을 빼서 11장만 남긴다
        analyzed.remove(0);
        // 실행+확인: 11장짜리 목록으로 저장을 부르면 예외가 나야 한다
        assertThatThrownBy(() -> service.save(BOOTH, set.getPublicId(), "v", List.of(), analyzed))
                // 확인: 도메인 예외이고 오류 코드가 입력값 검증 실패(400)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        // 확인: 일부만 분석한 결과로 등급 판정에 넘어가지 않았어야 한다
        verify(grading, never()).gradeIfReady(any());
    }

    // [상황] 12장 전부를 분석했고 흠집은 하나도 없다(빈 결과).
    // [기대] 받아들이고(저장된 흠집 0개) 탐지 받은 시각을 남긴 뒤 등급 판정으로 넘긴다.
    @Test
    @DisplayName("TOP·BOT 사진 전부를 분석했으면 흠집이 없어도(빈 detections) 받아서 등급 판정으로 넘긴다")
    void fullAccepted() {
        // (도구) assertThat(값).isZero() = 값이 0이어야 한다는 검사
        // 실행+확인: 12장 전부 분석, 흠집 0개로 저장하면 받아들이고 저장된 흠집 수 0을 돌려줘야 한다
        assertThat(service.save(BOOTH, set.getPublicId(), "v", List.of(), all())).isZero();
        // (도구) isNotNull() = 값이 비어 있지(null) 않아야 한다는 검사
        // 확인: 탐지 결과를 받은 시각이 세트에 기록됐어야 한다
        assertThat(set.getDetectionsReceivedAt()).isNotNull();
        // (도구) verify(가짜).메서드(값) = 그 메서드가 이 값으로 불렸어야 한다는 검사
        // 확인: 이 세트로 등급 판정을 불렀어야 한다
        verify(grading).gradeIfReady(set);
    }

    // 테스트 도구: 12장 전부를 "분석함" 목록으로 만든다.
    private static List<PhotoRef> all() {
        // 빈 목록을 만든다
        List<PhotoRef> refs = new ArrayList<>();
        // 12장 사진 하나하나에 대해
        for (PhotoSpec s : specs()) {
            // 면·조명·카메라만 뽑아 "분석함" 항목으로 넣는다
            refs.add(new PhotoRef(s.face(), s.light(), s.camera()));
        }
        // 완성된 12개 목록을 돌려준다
        return refs;
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 모든 사진에 같은 촬영 시각을 쓴다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
        // 바닥(BOT)·윗면(TOP) 두 면마다
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            // 조명 L1·L2·L3 세 가지마다
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                // 카메라 1번 사진 하나 (jpg, 가짜 해시)
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                // 카메라 2번 사진 하나 → 2면 × 3조명 × 2카메라 = 12장
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        // 완성된 12장 목록을 돌려준다
        return specs;
    }
}
