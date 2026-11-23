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

    private static final String SHA = "a".repeat(64);

    // [상황] 검사 방식 코드와 빠른 검사 필수 면을 확인한다.
    // [기대] 방식은 codes.yaml과 같은 FULL·QUICK 두 가지이고, 부스 빠른 검사 필수 면은 TOP·BOT, 부스 전체 검사는 기존 부스 필수 면과 같다.
    @Test
    @DisplayName("코드 enum은 codes.yaml capture.profile 값과 같고, 빠른 검사 필수 면은 face_required_quick.BOOTH(TOP·BOT)")
    void profileCodes() {
        assertThat(List.of(CaptureProfile.values())).extracting(Enum::name).containsExactly("FULL", "QUICK");
        assertThat(FaceRules.required(CaptureSource.BOOTH, CaptureProfile.QUICK)).containsExactly(Face.TOP, Face.BOT);
        assertThat(FaceRules.required(CaptureSource.BOOTH, CaptureProfile.FULL))
                .isEqualTo(FaceRules.required(CaptureSource.BOOTH));
    }

    // [상황] 방식을 정하지 않고 부스 세트를 만든 뒤 TOP·BOT 사진만으로 완료하려 한다.
    // [기대] 기본 방식은 전체 검사라 8면이 없으면 409 CAPTURE_FACES_MISSING(빠진 면 있음)으로 완료되지 않는다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("profile 없이 만든 부스 세트는 FULL이고 지금처럼 8면이 있어야 완료된다")
    void defaultIsFull() {
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", quickSpecs());
        assertThat(set.getProfile()).isEqualTo(CaptureProfile.FULL);
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, set.getProfile(), quickStates(SHA), Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING));
    }

    // [상황] 빠른 검사 세트를 만들고 TOP·BOT 사진(두 카메라)만 올렸다.
    // [기대] 그것만으로 완료된다.
    @Test
    @DisplayName("빠른 검사 세트는 TOP·BOT 사진 4장만으로 완료된다")
    void quickCompletesWithTwoFaces() {
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", null, CaptureProfile.QUICK, quickSpecs());
        assertThat(set.isQuick()).isTrue();
        assertThatCode(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, quickStates(SHA), Map.of()))
                .doesNotThrowAnyException();
    }

    // [상황] 빠른 검사에서 바닥(BOT) 사진이 빠졌고, 또 BOT을 "뒤집지 못함"으로 건너뛰려 한다.
    // [기대] 빠진 면 BOT으로 409를 내고, BOT은 필수 면이라 건너뛰기도 400으로 거절한다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("빠른 검사 세트에서 BOT이 빠지면 409 CAPTURE_FACES_MISSING, BOT은 건너뛸 수도 없다")
    void quickMissingBottom() {
        List<PhotoState> states = quickStates(SHA);
        states.removeIf(p -> p.face() == Face.BOT);
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, states, Map.of()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.BOT));
                });
        assertThatThrownBy(() -> CompletionCheck.checkSkippable(CaptureSource.BOOTH, CaptureProfile.QUICK,
                Map.of(Face.BOT, "뒤집지 못함")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // [상황] 빠른 검사에서 윗면(TOP)이 CAM1 사진만 올라오고 CAM2 사진이 없다.
    // [기대] 반만 찍힌 것이므로 TOP을 빠진 면으로 보고 409를 낸다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("빠른 검사 세트에서 한 면이 CAM1으로만 올라오면 반만 찍힌 것이라 409 CAPTURE_FACES_MISSING")
    void quickNeedsBothCameras() {
        List<PhotoState> states = quickStates(SHA);
        states.removeIf(p -> p.face() == Face.TOP && p.camera() == Camera.CAM2);
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, states, Map.of()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.TOP));
                });
    }

    // [상황] 폰 촬영으로 빠른 검사 세트를 만들려 한다.
    // [기대] 빠른 검사는 부스 전용이라 400(profile 칸 오류)으로 거절한다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("폰으로 빠른 검사 세트를 만들면 400(profile)")
    void quickPhoneRejected() {
        List<PhotoSpec> phone = List.of(new PhotoSpec(Face.TOP, Light.ROOM, Camera.PHONE, "jpg", SHA, now()));
        assertThatThrownBy(() -> CaptureSet.open("r", "c", CaptureSource.PHONE, null, null, CaptureProfile.QUICK, phone))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).extracting(DomainException.FieldError::field).containsExactly("profile");
                });
    }

    // [상황] 빠른 검사 세트를 완료 전과 완료 후에 "바로 등급" 처리하고, 전체 검사 세트에도 같은 처리를 해 본다.
    // [기대] 빠른 검사는 완료(UPLOADED) 뒤에만 정합 단계 없이 바로 등급(GRADED)이 된다.
    //        완료 전이거나 전체 검사 세트면 거절한다(전체 검사는 면 펼치기·정합을 거쳐야 함).
    @Test
    @DisplayName("빠른 검사 세트만 완료(UPLOADED)에서 정합 없이 바로 GRADED가 된다")
    void quickGradedFromUploaded() {
        CaptureSet quick = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", null, CaptureProfile.QUICK, quickSpecs());
        assertThatThrownBy(quick::markGradedQuick).isInstanceOf(IllegalStateException.class); // 완료 전
        quick.complete(Set.of(), Map.of(), "key-0001", SHA);
        quick.markGradedQuick();
        assertThat(quick.getStatus()).isEqualTo(SetStatus.GRADED);

        CaptureSet full = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", quickSpecs());
        full.complete(Set.of(), Map.of(), "key-0002", SHA);
        assertThatThrownBy(full::markGradedQuick).isInstanceOf(IllegalStateException.class);
    }

    // 테스트 도구: 빠른 검사 최소 사진 목록(BOT·TOP × CAM1·CAM2, L1)을 만든다.
    static List<PhotoSpec> quickSpecs() {
        List<PhotoSpec> specs = new ArrayList<>();
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM1, "jpg", SHA, now()));
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM2, "jpg", SHA, now()));
        }
        return specs;
    }

    // 테스트 도구: 위 사진들이 실제로 올라온 상태(올라온 파일의 SHA-256)를 만든다.
    private static List<PhotoState> quickStates(String actual) {
        List<PhotoState> states = new ArrayList<>();
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            states.add(new PhotoState(face, Light.L1, Camera.CAM1, SHA, actual));
            states.add(new PhotoState(face, Light.L1, Camera.CAM2, SHA, actual));
        }
        return states;
    }

    // 테스트 도구: 시험용 고정 촬영 시각.
    private static OffsetDateTime now() {
        return OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
    }
}
