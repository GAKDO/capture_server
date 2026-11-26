// 이 파일이 확인하는 것: 부스 빠른 검사 세트 규칙(contracts specs/005-booth-quick-set, 11/3 MVP).
// 빠른 검사는 바닥(BOT)·윗면(TOP)만 찍는 부스 전용 방식이고, 전체 검사(FULL)는 8면을 찍는다.
// 어떤 사진이 있어야 "촬영 완료"가 되는지, 언제 등급(GRADED)으로 넘어가는지를 본다.

package com.gakdo.trace.capture.captureset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.CompletionCheck;
import com.gakdo.trace.capture.captureset.domain.CompletionCheck.PhotoState;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.FaceRules;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;

/** 부스 빠른 검사 세트(contracts specs/005-booth-quick-set, 11/3 MVP). Docker 없이 도는 도메인 시험. */
class QuickSetDomainTest {

    // 가짜 사진 지문(SHA-256, 64자리 16진수). 부스가 "이 사진을 올린다"고 알릴 때 함께 보내는 값이다.
    // PhotoState(면, 조명, 카메라, 알린 지문, 실제로 올라온 파일의 지문): 마지막 값이 null이면 아직 올라오지 않은 사진이다.
    private static final String SHA = "a".repeat(64);

    // [상황] 검사 방식 코드와 빠른 검사 필수 면을 확인한다.
    // [기대] 방식은 codes.yaml과 같은 FULL·QUICK 두 가지이고, 부스 빠른 검사 필수 면은 TOP·BOT, 부스 전체 검사는 기존 부스 필수 면과 같다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("코드 enum은 codes.yaml capture.profile 값과 같고, 빠른 검사 필수 면은 face_required_quick.BOOTH(TOP·BOT)")
    void profileCodes() {
        // (도구) assertThat(목록).extracting(칸).containsExactly(...) = 목록 각 항목에서 그 칸만 뽑아, 정확히 이 값들이 이 순서로 있어야 한다는 검사
        // 실행+확인: 검사 방식 코드 이름은 FULL, QUICK 두 개여야 한다 (codes.yaml과 같아야 서버끼리 말이 통함)
        assertThat(List.of(CaptureProfile.values())).extracting(Enum::name).containsExactly("FULL", "QUICK");
        // 실행+확인: 부스 빠른 검사의 필수 면은 정확히 윗면(TOP), 바닥(BOT)이어야 한다
        assertThat(FaceRules.required(CaptureSource.BOOTH, CaptureProfile.QUICK)).containsExactly(Face.TOP, Face.BOT);
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 실행+확인: 부스 전체 검사의 필수 면은 방식을 안 줬을 때의 기존 부스 필수 면과 같아야 한다
        assertThat(FaceRules.required(CaptureSource.BOOTH, CaptureProfile.FULL))
                .isEqualTo(FaceRules.required(CaptureSource.BOOTH));
    }

    // [상황] 방식을 정하지 않고 부스 세트를 만든 뒤 TOP·BOT 사진만으로 완료하려 한다.
    // [기대] 기본 방식은 전체 검사라 8면이 없으면 409 CAPTURE_FACES_MISSING(빠진 면 있음)으로 완료되지 않는다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("profile 없이 만든 부스 세트는 FULL이고 지금처럼 8면이 있어야 완료된다")
    void defaultIsFull() {
        // 준비: 방식을 주지 않고 TOP·BOT 사진 4장 목록으로 부스 세트를 연다
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", quickSpecs());
        // 확인: 방식이 없으면 기본값 전체 검사(FULL)여야 한다
        assertThat(set.getProfile()).isEqualTo(CaptureProfile.FULL);
        // (도구) assertThatThrownBy(() -> 코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: TOP·BOT 4장이 다 올라온 상태로 완료 검사를 하면 예외가 나야 한다
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, set.getProfile(), quickStates(SHA), Map.of()))
                // (도구) isInstanceOfSatisfying(종류, e -> ...) = 예외가 그 종류이고, 안의 검사도 통과해야 한다는 뜻
                // 확인: 오류 코드는 빠진 면 있음(409)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING));
    }

    // [상황] 빠른 검사 세트를 만들고 TOP·BOT 사진(두 카메라)만 올렸다.
    // [기대] 그것만으로 완료된다.
    @Test
    @DisplayName("빠른 검사 세트는 TOP·BOT 사진 4장만으로 완료된다")
    void quickCompletesWithTwoFaces() {
        // 준비: 빠른 검사(QUICK) 방식으로 부스 세트를 연다
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", null, CaptureProfile.QUICK, quickSpecs());
        // (도구) isTrue() = 값이 참이어야 한다는 검사
        // 확인: 빠른 검사 세트로 표시돼야 한다
        assertThat(set.isQuick()).isTrue();
        // (도구) assertThatCode(() -> 코드).doesNotThrowAnyException() = 그 코드를 실행해도 예외가 나지 않아야 한다는 검사
        // 실행+확인: TOP·BOT 4장만 올라온 상태로 완료 검사를 해도 통과해야 한다
        assertThatCode(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, quickStates(SHA), Map.of()))
                .doesNotThrowAnyException();
    }

    // [상황] 빠른 검사에서 바닥(BOT) 사진이 빠졌고, 또 BOT을 "뒤집지 못함"으로 건너뛰려 한다.
    // [기대] 빠진 면 BOT으로 409를 내고, BOT은 필수 면이라 건너뛰기도 400으로 거절한다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("빠른 검사 세트에서 BOT이 빠지면 409 CAPTURE_FACES_MISSING, BOT은 건너뛸 수도 없다")
    void quickMissingBottom() {
        // 준비: TOP·BOT 4장이 다 올라온 상태 목록을 만든다
        List<PhotoState> states = quickStates(SHA);
        // 준비: 그중 바닥(BOT) 사진을 모두 뺀다
        states.removeIf(p -> p.face() == Face.BOT);
        // 실행+확인: 이 상태로 완료 검사를 하면 예외가 나야 한다
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, states, Map.of()))
                // 확인: 도메인 예외이고 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // 확인: 오류 코드는 빠진 면 있음(409)이어야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    // (도구) containsEntry(키, 값) = 맵에 그 키가 그 값으로 들어 있어야 한다는 검사
                    // 확인: 빠진 면 목록에 BOT 하나가 담겨야 한다 (어느 면을 다시 찍을지 알려 주려고)
                    assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.BOT));
                });
        // 실행+확인: BOT을 "뒤집지 못함" 이유로 건너뛰겠다고 하면 예외가 나야 한다
        assertThatThrownBy(() -> CompletionCheck.checkSkippable(CaptureSource.BOOTH, CaptureProfile.QUICK,
                Map.of(Face.BOT, "뒤집지 못함")))
                // 확인: 필수 면은 건너뛸 수 없어 입력값 검증 실패(400)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // [상황] 빠른 검사에서 윗면(TOP)이 CAM1 사진만 올라오고 CAM2 사진이 없다.
    // [기대] 반만 찍힌 것이므로 TOP을 빠진 면으로 보고 409를 낸다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("빠른 검사 세트에서 한 면이 CAM1으로만 올라오면 반만 찍힌 것이라 409 CAPTURE_FACES_MISSING")
    void quickNeedsBothCameras() {
        // 준비: TOP·BOT 4장이 다 올라온 상태 목록을 만든다
        List<PhotoState> states = quickStates(SHA);
        // 준비: 윗면(TOP)의 CAM2 사진만 뺀다
        states.removeIf(p -> p.face() == Face.TOP && p.camera() == Camera.CAM2);
        // 실행+확인: 이 상태로 완료 검사를 하면 예외가 나야 한다
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, states, Map.of()))
                // 확인: 도메인 예외이고 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // 확인: 오류 코드는 빠진 면 있음(409)이어야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    // 확인: 반만 찍힌 TOP이 빠진 면으로 잡혀야 한다
                    assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.TOP));
                });
    }

    // [상황] 폰 촬영으로 빠른 검사 세트를 만들려 한다.
    // [기대] 빠른 검사는 부스 전용이라 400(profile 칸 오류)으로 거절한다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("폰으로 빠른 검사 세트를 만들면 400(profile)")
    void quickPhoneRejected() {
        // 준비: 폰으로 찍은 윗면 사진 한 장(실내 조명) 목록을 만든다
        List<PhotoSpec> phone = List.of(new PhotoSpec(Face.TOP, Light.ROOM, Camera.PHONE, "jpg", SHA, now()));
        // 실행+확인: 폰 촬영으로 빠른 검사 세트를 열면 예외가 나야 한다
        assertThatThrownBy(() -> CaptureSet.open("r", "c", CaptureSource.PHONE, null, null, CaptureProfile.QUICK, phone))
                // 확인: 도메인 예외이고 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // 확인: 오류 코드는 입력값 검증 실패(400)여야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    // 확인: 잘못된 칸은 profile 하나뿐이어야 한다
                    assertThat(e.errors()).extracting(DomainException.FieldError::field).containsExactly("profile");
                });
    }

    // [상황] 빠른 검사 세트를 완료 전과 완료 후에 "바로 등급" 처리하고, 전체 검사 세트에도 같은 처리를 해 본다.
    // [기대] 빠른 검사는 완료(UPLOADED) 뒤에만 정합 단계 없이 바로 등급(GRADED)이 된다.
    //        완료 전이거나 전체 검사 세트면 거절한다(전체 검사는 면 펼치기·정합을 거쳐야 함).
    @Test
    @DisplayName("빠른 검사 세트만 완료(UPLOADED)에서 정합 없이 바로 GRADED가 된다")
    void quickGradedFromUploaded() {
        // 준비: 빠른 검사 세트를 연다 (아직 완료 전)
        CaptureSet quick = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", null, CaptureProfile.QUICK, quickSpecs());
        // (도구) isInstanceOf(종류) = 예외가 그 종류여야 한다는 검사. quick::markGradedQuick = "quick의 markGradedQuick을 실행"을 짧게 쓴 것
        // 실행+확인: 완료 전에 바로 등급 처리를 하면 잘못된 상태 예외가 나야 한다
        assertThatThrownBy(quick::markGradedQuick).isInstanceOf(IllegalStateException.class); // 완료 전
        // 준비: 세트를 완료(UPLOADED)한다 (요청 키 key-0001)
        quick.complete(Set.of(), Map.of(), "key-0001", SHA);
        // 실행: 이제 바로 등급 처리를 한다
        quick.markGradedQuick();
        // 확인: 정합 단계 없이 등급(GRADED) 상태가 돼야 한다
        assertThat(quick.getStatus()).isEqualTo(SetStatus.GRADED);

        // 준비: 방식 없이(=전체 검사) 부스 세트를 연다
        CaptureSet full = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", quickSpecs());
        // 준비: 이 세트도 완료한다
        full.complete(Set.of(), Map.of(), "key-0002", SHA);
        // 실행+확인: 전체 검사 세트를 바로 등급 처리하면 잘못된 상태 예외가 나야 한다 (정합을 거쳐야 하므로)
        assertThatThrownBy(full::markGradedQuick).isInstanceOf(IllegalStateException.class);
    }

    // 테스트 도구: 빠른 검사 최소 사진 목록(BOT·TOP × CAM1·CAM2, L1)을 만든다.
    static List<PhotoSpec> quickSpecs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 바닥·윗면마다
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            // CAM1 사진 한 장 (조명 L1, jpg)
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM1, "jpg", SHA, now()));
            // CAM2 사진 한 장 → 모두 4장
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM2, "jpg", SHA, now()));
        }
        // 완성된 4장 목록을 돌려준다
        return specs;
    }

    // 테스트 도구: 위 사진들이 실제로 올라온 상태(올라온 파일의 SHA-256)를 만든다.
    private static List<PhotoState> quickStates(String actual) {
        // 빈 목록을 만든다
        List<PhotoState> states = new ArrayList<>();
        // 바닥·윗면마다
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            // CAM1 사진: 알린 지문 SHA, 실제 올라온 지문 actual
            states.add(new PhotoState(face, Light.L1, Camera.CAM1, SHA, actual));
            // CAM2 사진도 같은 방식으로
            states.add(new PhotoState(face, Light.L1, Camera.CAM2, SHA, actual));
        }
        // 완성된 4장 상태 목록을 돌려준다
        return states;
    }

    // 테스트 도구: 시험용 고정 촬영 시각.
    private static OffsetDateTime now() {
        // 2026-11-05 오전 10시(한국 시간)를 돌려준다
        return OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
    }
}
