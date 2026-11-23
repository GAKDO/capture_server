// 이 파일이 확인하는 것: 촬영 세트의 면 규칙과 "촬영 완료" 판정. DB 없이 도는 시험이다.
// 촬영 세트 = 한 시점에 한 기기를 찍은 사진 묶음. 부스(BOOTH)는 CAM1·CAM2 두 카메라, 폰(PHONE)은 한 장씩 찍는다.
// 코드 값이 계약(codes.yaml)과 같은지, 필수 면이 다 올라왔는지, 올라온 파일이 알린 해시와 같은지를 본다.

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
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.Quality;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;

/** 저장소·DB 없이 도는 면 규칙·완료 판정 시험. */
class CaptureDomainTest {

    private static final String SHA = "a".repeat(64);
    private static final String OTHER_SHA = "b".repeat(64);

    // [상황] 서버의 코드 목록(촬영 출처·면·조명·카메라·세트 상태·품질·오류 코드)을 계약과 비교한다.
    // [기대] 이름과 순서가 contracts codes.yaml 0.6.0과 같고, 오류 코드는 모두 계약에 있는 값이다.
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

    // [상황] 출처별 필수 면을 확인한다.
    // [기대] 부스는 8면(윗면·바닥·옆면 4개·키보드·화면 SCR0), 폰은 키보드를 뺀 7면이다. 흰 화면 SCR1은 둘 다 선택이다.
    @Test
    @DisplayName("필수 면은 codes.yaml capture.face_required: 부스 8면, 폰 7면(KB 제외), SCR1은 둘 다 선택")
    void requiredFaces() {
        assertThat(FaceRules.required(CaptureSource.BOOTH)).containsExactly(Face.TOP, Face.BOT, Face.SF, Face.SB,
                Face.SL, Face.SR, Face.KB, Face.SCR0);
        assertThat(FaceRules.required(CaptureSource.PHONE)).containsExactly(Face.TOP, Face.BOT, Face.SF, Face.SB,
                Face.SL, Face.SR, Face.SCR0);
    }

    // [상황] 같은 (면, 조명, 카메라) 사진을 두 번 알리거나, 폰 세트인데 부스 조명(L1)을 적었다.
    // [기대] 400으로 거절하고 어느 칸이 틀렸는지(photos[1], photos[0].light) 알려 준다.
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

    // [상황] 부스 세트에 같은 면·조명(윗면 L2)을 CAM1·CAM2 두 장으로 알린다.
    // [기대] 두 사진의 저장 파일 이름(객체 키)이 서로 다르고 세트 폴더 아래에 있으며, 세트는 "올리는 중"(UPLOADING)이다.
    @Test
    @DisplayName("부스는 같은 면·조명을 CAM1·CAM2로 두 장 찍고, 객체 키가 서로 다르다")
    void boothTwoCameras() {
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b",
                List.of(spec(Face.TOP, Light.L2, Camera.CAM1), spec(Face.TOP, Light.L2, Camera.CAM2)));
        assertThat(set.getPhotos()).extracting(Photo::getObjectKey).doesNotHaveDuplicates()
                .allMatch(k -> k.startsWith("captures/" + set.getPublicId() + "/TOP-L2-"));
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADING);
    }

    // [상황] 부스 세트에서 윗면과 키보드 사진이 빠진 채 완료하려 한다.
    // [기대] 409 CAPTURE_FACES_MISSING과 빠진 면 목록(TOP, KB, 계약 순서)을 돌려준다.
    @Test
    @DisplayName("세트 출처의 필수 면이 빠지면 409 CAPTURE_FACES_MISSING과 빠진 면 목록(codes.yaml 순서)")
    void missingFaces() {
        List<PhotoState> photos = allBooth(SHA);
        photos.removeIf(p -> p.face() == Face.KB || p.face() == Face.TOP);
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.TOP, Face.KB));
                });
    }

    // [상황] 윗면 사진 중 하나(L2 CAM2)를 알렸지만 실제로 올라가지 않았다.
    // [기대] 윗면을 빠진 면으로 본다(일부만 올라간 면은 완료로 치지 않음).
    @Test
    @DisplayName("필수 면의 알린 사진 중 하나라도 올라가지 않으면 그 면은 빠진 면이다")
    void partialFace() {
        List<PhotoState> photos = allBooth(SHA);
        photos.add(new PhotoState(Face.TOP, Light.L2, Camera.CAM2, SHA, null));
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.TOP)));
    }

    // [상황] 폰 세트가 필수 7면만 올리고 키보드는 없다.
    // [기대] 완료된다.
    @Test
    @DisplayName("폰의 필수 면은 7면이라 KB 없이 완료된다")
    void phoneWithoutKb() {
        List<PhotoState> photos = new ArrayList<>();
        for (Face face : FaceRules.required(CaptureSource.PHONE)) {
            photos.add(new PhotoState(face, Light.ROOM, Camera.PHONE, SHA, SHA));
        }
        CompletionCheck.check(CaptureSource.PHONE, photos, Map.of());
    }

    // [상황] 흰 화면(SCR1) 사진을 알렸지만 올리지 않았다. 한 번은 건너뛴 이유("화면이 켜지지 않음")를 적고, 한 번은 안 적는다.
    // [기대] 이유가 있으면 통과, 없으면 400(skippedFaces.SCR1 칸 오류)이다.
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

    // [상황] 부스 세트에서 필수 면인 키보드를 건너뛰려 한다. 폰 세트에서도 키보드를 건너뛰려 한다.
    // [기대] 부스는 400(skippedFaces.KB)으로 거절하고, 폰은 키보드가 필수가 아니라 통과한다.
    @Test
    @DisplayName("필수 면은 skippedFaces에 넣을 수 없다(400, skippedFaces.KB)")
    void requiredNotSkippable() {
        assertThatThrownBy(() -> CompletionCheck.checkSkippable(CaptureSource.BOOTH, Map.of(Face.KB, "x")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errors())
                        .extracting(DomainException.FieldError::field).containsExactly("skippedFaces.KB"));
        CompletionCheck.checkSkippable(CaptureSource.PHONE, Map.of(Face.KB, "폰은 KB를 찍지 않음"));
    }

    // [상황] 올라간 사진 하나의 SHA-256이 처음 알린 값과 다르다(바뀌었거나 다른 파일).
    // [기대] 409 CAPTURE_HASH_MISMATCH로 완료를 거절한다(증거 사진 바꿔치기 방지).
    @Test
    @DisplayName("올라간 사진의 해시가 알린 값과 다르면 409 CAPTURE_HASH_MISMATCH")
    void hashMismatch() {
        List<PhotoState> photos = allBooth(SHA);
        photos.set(0, new PhotoState(Face.TOP, Light.L1, Camera.CAM1, SHA, OTHER_SHA));
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_HASH_MISMATCH));
    }

    // [상황] 세트를 완료한 뒤 한 번 더 완료하려 한다.
    // [기대] 처음엔 "올림 완료"(UPLOADED)가 되고 사진이 올라간 것으로 표시된다. 두 번째는 409 CAPTURE_SET_CLOSED로 거절한다.
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

    // 테스트 도구: 부스 필수 8면을 CAM1·CAM2로 다 올린 상태를 만든다.
    private static List<PhotoState> allBooth(String actual) {
        List<PhotoState> photos = new ArrayList<>();
        for (Face face : FaceRules.required(CaptureSource.BOOTH)) {
            photos.add(new PhotoState(face, Light.L1, Camera.CAM1, SHA, actual));
            photos.add(new PhotoState(face, Light.L1, Camera.CAM2, SHA, actual));
        }
        return photos;
    }

    // 테스트 도구: 사진 하나를 알리는 항목(면·조명·카메라, jpg, SHA-256, 촬영 시각)을 만든다.
    private static PhotoSpec spec(Face face, Light light, Camera camera) {
        return new PhotoSpec(face, light, camera, "jpg", SHA, OffsetDateTime.parse("2026-11-12T13:05:00+09:00"));
    }

    // 테스트 도구: 코드 목록을 이름 문자열 목록으로 바꾼다.
    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** contracts codes.yaml 0.6.0 error_code 중 이 서버가 쓰는 것을 포함한 전체 목록의 일부(공통 + capture). */
    // 테스트 도구: 계약 codes.yaml 0.6.0의 오류 코드 중 비교에 쓰는 목록(공통 + 촬영·검수).
    private static final Set<String> CONTRACT_ERROR_CODES = Set.of("VALIDATION_FAILED", "UNAUTHENTICATED", "FORBIDDEN",
            "OUT_OF_SCOPE", "NOT_FOUND", "RATE_LIMITED", "IDEMPOTENCY_KEY_CONFLICT", "INTERNAL_ERROR",
            "CAPTURE_FACES_MISSING", "CAPTURE_HASH_MISMATCH", "CAPTURE_SET_CLOSED", "REVIEW_PENDING_REMAINS",
            "REVIEW_NOT_READY", "REVIEW_CLOSED");
}
