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

    @Test
    @DisplayName("코드 enum은 codes.yaml capture.profile 값과 같고, 빠른 검사 필수 면은 face_required_quick.BOOTH(TOP·BOT)")
    void profileCodes() {
        assertThat(List.of(CaptureProfile.values())).extracting(Enum::name).containsExactly("FULL", "QUICK");
        assertThat(FaceRules.required(CaptureSource.BOOTH, CaptureProfile.QUICK)).containsExactly(Face.TOP, Face.BOT);
        assertThat(FaceRules.required(CaptureSource.BOOTH, CaptureProfile.FULL))
                .isEqualTo(FaceRules.required(CaptureSource.BOOTH));
    }

    @Test
    @DisplayName("profile 없이 만든 부스 세트는 FULL이고 지금처럼 8면이 있어야 완료된다")
    void defaultIsFull() {
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", quickSpecs());
        assertThat(set.getProfile()).isEqualTo(CaptureProfile.FULL);
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, set.getProfile(), quickStates(SHA), Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING));
    }

    @Test
    @DisplayName("빠른 검사 세트는 TOP·BOT 사진 4장만으로 완료된다")
    void quickCompletesWithTwoFaces() {
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", null, CaptureProfile.QUICK, quickSpecs());
        assertThat(set.isQuick()).isTrue();
        assertThatCode(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, quickStates(SHA), Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("빠른 검사 세트에서 BOT이 빠지면 409 CAPTURE_FACES_MISSING, BOT은 건너뛸 수도 없다")
    void quickMissingBottom() {
        List<PhotoState> states = quickStates(SHA);
        states.removeIf(p -> p.face() == Face.BOT);
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, CaptureProfile.QUICK, states, Map.of()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    assertThat(e.properties().get("missingFaces")).isEqualTo(List.of(Face.BOT));
                });
        assertThatThrownBy(() -> CompletionCheck.checkSkippable(CaptureSource.BOOTH, CaptureProfile.QUICK,
                Map.of(Face.BOT, "뒤집지 못함")))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

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

    static List<PhotoSpec> quickSpecs() {
        List<PhotoSpec> specs = new ArrayList<>();
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM1, "jpg", SHA, now()));
            specs.add(new PhotoSpec(face, Light.L1, Camera.CAM2, "jpg", SHA, now()));
        }
        return specs;
    }

    private static List<PhotoState> quickStates(String actual) {
        List<PhotoState> states = new ArrayList<>();
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            states.add(new PhotoState(face, Light.L1, Camera.CAM1, SHA, actual));
            states.add(new PhotoState(face, Light.L1, Camera.CAM2, SHA, actual));
        }
        return states;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
    }
}
