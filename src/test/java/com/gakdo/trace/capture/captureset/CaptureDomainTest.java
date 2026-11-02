package com.gakdo.trace.capture.captureset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.CompletionCheck;
import com.gakdo.trace.capture.captureset.domain.CompletionCheck.PhotoState;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.FaceRules;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Quality;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;

/** 저장소·DB 없이 도는 면 규칙·완료 판정 시험. */
class CaptureDomainTest {

    private static final String SHA = "a".repeat(64);
    private static final String OTHER_SHA = "b".repeat(64);

    @Test
    @DisplayName("코드 enum은 contracts codes.yaml 0.6.0 capture·error_code 값과 이름·순서가 같다")
    void codesMatchContract() {
        assertThat(names(CaptureSource.values())).containsExactly("BOOTH", "PHONE");
        assertThat(names(Face.values())).containsExactly("TOP", "BOT", "SF", "SB", "SL", "SR", "KB", "SCR0", "SCR1");
        assertThat(names(Light.values())).containsExactly("L1", "L2", "L3", "L4", "ROOM", "OFF");
        assertThat(names(Camera.values())).containsExactly("CAM1", "CAM2", "PHONE");
        assertThat(names(SetStatus.values())).containsExactly("UPLOADING", "UPLOADED", "INSPECTED", "GRADED");
        assertThat(names(Quality.values())).containsExactly("OK", "UNJUDGEABLE");
        assertThat(names(ErrorCode.values())).allMatch(CONTRACT_ERROR_CODES::contains);
    }

    @Test
    @DisplayName("필수 면은 codes.yaml capture.face_required: 부스 8면, 폰 7면(KB 제외), SCR1은 둘 다 선택")
    void requiredFaces() {
        assertThat(FaceRules.required(CaptureSource.BOOTH)).containsExactly(Face.TOP, Face.BOT, Face.SF, Face.SB,
                Face.SL, Face.SR, Face.KB, Face.SCR0);
        assertThat(FaceRules.required(CaptureSource.PHONE)).containsExactly(Face.TOP, Face.BOT, Face.SF, Face.SB,
                Face.SL, Face.SR, Face.SCR0);
    }

    @Test
    @DisplayName("같은 세트 안에 (면, 조명, 카메라)가 겹치거나 출처와 맞지 않는 카메라·조명이면 400")
    void openValidates() {
        List<PhotoSpec> dup = List.of(spec(Face.TOP, Light.L1, Camera.CAM1), spec(Face.TOP, Light.L1, Camera.CAM1));
        assertThatThrownBy(() -> CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", dup))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).extracting(DomainException.FieldError::field).containsExactly("photos[1]");
                });
        List<PhotoSpec> wrong = List.of(spec(Face.TOP, Light.L1, Camera.PHONE));
        assertThatThrownBy(() -> CaptureSet.open("r", "c", CaptureSource.PHONE, null, wrong))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errors())
                        .extracting(DomainException.FieldError::field).containsExactly("photos[0].light"));
    }

    @Test
    @DisplayName("부스는 같은 면·조명을 CAM1·CAM2로 두 장 찍고, 객체 키가 서로 다르다")
    void boothTwoCameras() {
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b",
                List.of(spec(Face.TOP, Light.L2, Camera.CAM1), spec(Face.TOP, Light.L2, Camera.CAM2)));
        assertThat(set.getPhotos()).extracting(p -> p.getObjectKey()).doesNotHaveDuplicates()
                .allMatch(k -> k.startsWith("captures/" + set.getPublicId() + "/TOP-L2-"));
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADING);
    }

    @Test
    @DisplayName("세트 출처의 필수 면이 빠지면 409 CAPTURE_FACES_MISSING과 빠진 면 목록(codes.yaml 순서)")
    void missingFaces() {
        List<PhotoState> photos = allBooth(SHA);
        photos.removeIf(p -> p.face() == Face.KB || p.face() == Face.TOP);
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    assertThat(e.properties().get("missingFaces")).isEqualTo(List.of(Face.TOP, Face.KB));
                });
    }

    @Test
    @DisplayName("필수 면의 알린 사진 중 하나라도 올라가지 않으면 그 면은 빠진 면이다")
    void partialFace() {
        List<PhotoState> photos = allBooth(SHA);
        photos.add(new PhotoState(Face.TOP, Light.L2, Camera.CAM2, SHA, null));
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.properties().get("missingFaces")).isEqualTo(List.of(Face.TOP)));
    }

    @Test
    @DisplayName("폰의 필수 면은 7면이라 KB 없이 완료된다")
    void phoneWithoutKb() {
        List<PhotoState> photos = new ArrayList<>();
        for (Face face : FaceRules.required(CaptureSource.PHONE)) {
            photos.add(new PhotoState(face, Light.ROOM, Camera.PHONE, SHA, SHA));
        }
        CompletionCheck.check(CaptureSource.PHONE, photos, Map.of());
    }

    @Test
    @DisplayName("SCR1은 선택이고 건너뛸 때는 skippedFaces에 사유를 남긴다: 사유가 있으면 통과, 없으면 400")
    void optionalFaceNeedsReason() {
        List<PhotoState> photos = allBooth(SHA);
        photos.add(new PhotoState(Face.SCR1, Light.OFF, Camera.CAM1, SHA, null));
        CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of(Face.SCR1, "화면이 켜지지 않음"));
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).extracting(DomainException.FieldError::field)
                            .containsExactly("skippedFaces.SCR1");
                });
    }

    @Test
    @DisplayName("필수 면은 skippedFaces에 넣을 수 없다(400, skippedFaces.KB)")
    void requiredNotSkippable() {
        assertThatThrownBy(() -> CompletionCheck.checkSkippable(CaptureSource.BOOTH, Map.of(Face.KB, "x")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errors())
                        .extracting(DomainException.FieldError::field).containsExactly("skippedFaces.KB"));
        CompletionCheck.checkSkippable(CaptureSource.PHONE, Map.of(Face.KB, "폰은 KB를 찍지 않음"));
    }

    @Test
    @DisplayName("올라간 사진의 해시가 알린 값과 다르면 409 CAPTURE_HASH_MISMATCH")
    void hashMismatch() {
        List<PhotoState> photos = allBooth(SHA);
        photos.set(0, new PhotoState(Face.TOP, Light.L1, Camera.CAM1, SHA, OTHER_SHA));
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_HASH_MISMATCH));
    }

    @Test
    @DisplayName("완료된 세트는 다시 완료할 수 없다(409 CAPTURE_SET_CLOSED)")
    void completeOnce() {
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b",
                List.of(spec(Face.TOP, Light.L1, Camera.CAM1)));
        set.complete(Set.of(set.getPhotos().get(0).getPublicId()), Map.of(), "key-0001", SHA);
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        assertThat(set.getPhotos().get(0).isUploaded()).isTrue();
        assertThatThrownBy(() -> set.complete(Set.of(), Map.of(), "key-0002", SHA))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_SET_CLOSED));
    }

    private static List<PhotoState> allBooth(String actual) {
        List<PhotoState> photos = new ArrayList<>();
        for (Face face : FaceRules.required(CaptureSource.BOOTH)) {
            photos.add(new PhotoState(face, Light.L1, Camera.CAM1, SHA, actual));
            photos.add(new PhotoState(face, Light.L1, Camera.CAM2, SHA, actual));
        }
        return photos;
    }

    private static PhotoSpec spec(Face face, Light light, Camera camera) {
        return new PhotoSpec(face, light, camera, "jpg", SHA, OffsetDateTime.parse("2026-11-12T13:05:00+09:00"));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** contracts codes.yaml 0.6.0 error_code 중 이 서버가 쓰는 것을 포함한 전체 목록의 일부(공통 + capture). */
    private static final Set<String> CONTRACT_ERROR_CODES = Set.of("VALIDATION_FAILED", "UNAUTHENTICATED", "FORBIDDEN",
            "OUT_OF_SCOPE", "NOT_FOUND", "RATE_LIMITED", "IDEMPOTENCY_KEY_CONFLICT", "INTERNAL_ERROR",
            "CAPTURE_FACES_MISSING", "CAPTURE_HASH_MISMATCH", "CAPTURE_SET_CLOSED");
}
