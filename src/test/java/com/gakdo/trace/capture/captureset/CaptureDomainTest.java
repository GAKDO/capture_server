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

    // 가짜 사진 지문(SHA-256, 64자리 16진수). SHA는 "알린 지문", OTHER_SHA는 "실제로 올라온 파일이 다를 때"의 지문이다.
    // PhotoState(면, 조명, 카메라, 알린 지문, 실제로 올라온 파일의 지문): 마지막 값이 null이면 아직 올라오지 않은 사진이다.
    private static final String SHA = "a".repeat(64);
    // "실제로 올라온 파일이 다를 때" 쓰는 다른 지문(64자리 "b")
    private static final String OTHER_SHA = "b".repeat(64);

    // [상황] 서버의 코드 목록(촬영 출처·면·조명·카메라·세트 상태·품질·오류 코드)을 계약과 비교한다.
    // [기대] 이름과 순서가 contracts codes.yaml 0.6.0과 같고, 오류 코드는 모두 계약에 있는 값이다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("코드 enum은 contracts codes.yaml 0.6.0 capture·error_code 값과 이름·순서가 같다")
    void codesMatchContract() {
        // (도구) assertThat(목록).containsExactly(...) = 목록에 정확히 이 값들만 이 순서로 있어야 한다는 검사
        // 실행+확인: 촬영 출처 코드는 BOOTH, PHONE이어야 한다
        assertThat(names(CaptureSource.values())).containsExactly("BOOTH", "PHONE");
        // 실행+확인: 면 코드 9개가 계약과 같은 이름·순서여야 한다
        assertThat(names(Face.values())).containsExactly("TOP", "BOT", "SF", "SB", "SL", "SR", "KB", "SCR0", "SCR1");
        // 실행+확인: 조명 코드 6개가 계약과 같아야 한다
        assertThat(names(Light.values())).containsExactly("L1", "L2", "L3", "L4", "ROOM", "OFF");
        // 실행+확인: 카메라 코드 3개가 계약과 같아야 한다
        assertThat(names(Camera.values())).containsExactly("CAM1", "CAM2", "PHONE");
        // 실행+확인: 세트 상태 코드 4개가 계약과 같아야 한다
        assertThat(names(SetStatus.values())).containsExactly("UPLOADING", "UPLOADED", "INSPECTED", "GRADED");
        // 실행+확인: 품질 코드 2개가 계약과 같아야 한다
        assertThat(names(Quality.values())).containsExactly("OK", "UNJUDGEABLE");
        // (도구) allMatch(조건) = 목록의 모든 항목이 조건을 만족해야 한다는 검사
        // 실행+확인: 서버의 오류 코드는 모두 계약 목록 안에 있어야 한다 (계약에 없는 코드를 만들지 않게)
        assertThat(names(ErrorCode.values())).allMatch(CONTRACT_ERROR_CODES::contains);
    }

    // [상황] 출처별 필수 면을 확인한다.
    // [기대] 부스는 8면(윗면·바닥·옆면 4개·키보드·화면 SCR0), 폰은 키보드를 뺀 7면이다. 흰 화면 SCR1은 둘 다 선택이다.
    @Test
    @DisplayName("필수 면은 codes.yaml capture.face_required: 부스 8면, 폰 7면(KB 제외), SCR1은 둘 다 선택")
    void requiredFaces() {
        // 실행+확인: 부스 필수 면은 8면이 이 순서여야 한다
        assertThat(FaceRules.required(CaptureSource.BOOTH)).containsExactly(Face.TOP, Face.BOT, Face.SF, Face.SB,
                Face.SL, Face.SR, Face.KB, Face.SCR0);
        // 실행+확인: 폰 필수 면은 키보드를 뺀 7면이어야 한다
        assertThat(FaceRules.required(CaptureSource.PHONE)).containsExactly(Face.TOP, Face.BOT, Face.SF, Face.SB,
                Face.SL, Face.SR, Face.SCR0);
    }

    // [상황] 같은 (면, 조명, 카메라) 사진을 두 번 알리거나, 폰 세트인데 부스 조명(L1)을 적었다.
    // [기대] 400으로 거절하고 어느 칸이 틀렸는지(photos[1], photos[0].light) 알려 준다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("같은 세트 안에 (면, 조명, 카메라)가 겹치거나 출처와 맞지 않는 카메라·조명이면 400")
    void openValidates() {
        // 준비: 윗면·L1·CAM1 사진을 두 번 알리는 목록을 만든다
        List<PhotoSpec> dup = List.of(spec(Face.TOP, Light.L1, Camera.CAM1), spec(Face.TOP, Light.L1, Camera.CAM1));
        // (도구) assertThatThrownBy(() -> 코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 이 목록으로 부스 세트를 열면 예외가 나야 한다
        assertThatThrownBy(() -> CaptureSet.open("r", "c", CaptureSource.BOOTH, "b", dup))
                // (도구) isInstanceOfSatisfying(종류, e -> ...) = 예외가 그 종류이고, 안의 검사도 통과해야 한다는 뜻
                // 확인: 도메인 예외이고 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
                    // 확인: 오류 코드는 입력값 검증 실패(400)여야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    // (도구) extracting(칸) = 목록의 각 항목에서 그 칸만 뽑아 검사한다
                    // 확인: 틀린 칸은 두 번째 사진(photos[1])이어야 한다
                    assertThat(e.errors()).extracting(DomainException.FieldError::field).containsExactly("photos[1]");
                });
        // 준비: 폰 카메라인데 부스 조명 L1을 적은 사진 목록
        List<PhotoSpec> wrong = List.of(spec(Face.TOP, Light.L1, Camera.PHONE));
        // 실행+확인: 이 목록으로 폰 세트를 열면 예외가 나야 하고
        assertThatThrownBy(() -> CaptureSet.open("r", "c", CaptureSource.PHONE, null, wrong))
                // 확인: 틀린 칸은 첫 사진의 조명(photos[0].light)이어야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errors())
                        .extracting(DomainException.FieldError::field).containsExactly("photos[0].light"));
    }

    // [상황] 부스 세트에 같은 면·조명(윗면 L2)을 CAM1·CAM2 두 장으로 알린다.
    // [기대] 두 사진의 저장 파일 이름(객체 키)이 서로 다르고 세트 폴더 아래에 있으며, 세트는 "올리는 중"(UPLOADING)이다.
    @Test
    @DisplayName("부스는 같은 면·조명을 CAM1·CAM2로 두 장 찍고, 객체 키가 서로 다르다")
    void boothTwoCameras() {
        // 준비: 윗면·L2를 CAM1·CAM2 두 장으로 알린 부스 세트를 연다
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b",
                List.of(spec(Face.TOP, Light.L2, Camera.CAM1), spec(Face.TOP, Light.L2, Camera.CAM2)));
        // (도구) doesNotHaveDuplicates() = 겹치는 값이 없어야 함. allMatch(조건) = 모두 조건을 만족해야 함
        // 확인: 두 사진의 저장 파일 이름이 서로 다르고, 모두 "captures/세트ID/TOP-L2-"로 시작해야 한다
        assertThat(set.getPhotos()).extracting(Photo::getObjectKey).doesNotHaveDuplicates()
                .allMatch(k -> k.startsWith("captures/" + set.getPublicId() + "/TOP-L2-"));
        // 확인: 세트 상태는 올리는 중(UPLOADING)이어야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADING);
    }

    // [상황] 부스 세트에서 윗면과 키보드 사진이 빠진 채 완료하려 한다.
    // [기대] 409 CAPTURE_FACES_MISSING과 빠진 면 목록(TOP, KB, 계약 순서)을 돌려준다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("세트 출처의 필수 면이 빠지면 409 CAPTURE_FACES_MISSING과 빠진 면 목록(codes.yaml 순서)")
    void missingFaces() {
        // 준비: 부스 필수 8면을 다 올린 상태를 만든다
        List<PhotoState> photos = allBooth(SHA);
        // 준비: 그중 키보드와 윗면 사진을 뺀다
        photos.removeIf(p -> p.face() == Face.KB || p.face() == Face.TOP);
        // 실행+확인: 완료 검사를 하면 예외가 나야 한다
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                // 확인: 도메인 예외이고 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // 확인: 오류 코드는 빠진 면 있음(409)이어야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_FACES_MISSING);
                    // (도구) containsEntry(키, 값) = 맵에 그 키가 그 값으로 들어 있어야 한다는 검사
                    // 확인: 빠진 면 목록은 계약 순서대로 TOP, KB여야 한다
                    assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.TOP, Face.KB));
                });
    }

    // [상황] 윗면 사진 중 하나(L2 CAM2)를 알렸지만 실제로 올라가지 않았다.
    // [기대] 윗면을 빠진 면으로 본다(일부만 올라간 면은 완료로 치지 않음).
    @Test
    @DisplayName("필수 면의 알린 사진 중 하나라도 올라가지 않으면 그 면은 빠진 면이다")
    void partialFace() {
        // 준비: 부스 필수 8면을 다 올린 상태를 만든다
        List<PhotoState> photos = allBooth(SHA);
        // 준비: 윗면 L2 CAM2 사진을 알렸지만 안 올라온(null) 것으로 더한다
        photos.add(new PhotoState(Face.TOP, Light.L2, Camera.CAM2, SHA, null));
        // 실행+확인: 완료 검사를 하면 예외가 나야 하고
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                // 확인: 빠진 면 목록에 TOP이 담겨야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.properties()).containsEntry("missingFaces", List.of(Face.TOP)));
    }

    // [상황] 폰 세트가 필수 7면만 올리고 키보드는 없다.
    // [기대] 완료된다.
    @Test
    @DisplayName("폰의 필수 면은 7면이라 KB 없이 완료된다")
    void phoneWithoutKb() {
        // 준비: 빈 사진 상태 목록
        List<PhotoState> photos = new ArrayList<>();
        // 폰 필수 7면마다
        for (Face face : FaceRules.required(CaptureSource.PHONE)) {
            // 실내 조명 폰 사진 한 장을 올린 상태로 넣는다
            photos.add(new PhotoState(face, Light.ROOM, Camera.PHONE, SHA, SHA));
        }
        // 실행+확인: 완료 검사를 해도 예외가 나지 않아야 한다 (예외가 나면 테스트 실패)
        CompletionCheck.check(CaptureSource.PHONE, photos, Map.of());
    }

    // [상황] 흰 화면(SCR1) 사진을 알렸지만 올리지 않았다. 한 번은 건너뛴 이유("화면이 켜지지 않음")를 적고, 한 번은 안 적는다.
    // [기대] 이유가 있으면 통과, 없으면 400(skippedFaces.SCR1 칸 오류)이다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("SCR1은 선택이고 건너뛸 때는 skippedFaces에 사유를 남긴다: 사유가 있으면 통과, 없으면 400")
    void optionalFaceNeedsReason() {
        // 준비: 부스 필수 8면을 다 올린 상태를 만든다
        List<PhotoState> photos = allBooth(SHA);
        // 준비: 흰 화면(SCR1) 사진을 알렸지만 안 올라온 것으로 더한다
        photos.add(new PhotoState(Face.SCR1, Light.OFF, Camera.CAM1, SHA, null));
        // 실행+확인: 건너뛴 이유를 적으면 완료 검사가 통과해야 한다
        CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of(Face.SCR1, "화면이 켜지지 않음"));
        // 실행+확인: 이유 없이 완료 검사를 하면 예외가 나야 한다
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                // 확인: 도메인 예외이고 아래 두 가지를 만족해야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // 확인: 오류 코드는 입력값 검증 실패(400)여야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    // 확인: 틀린 칸은 skippedFaces.SCR1이어야 한다
                    assertThat(e.errors()).extracting(DomainException.FieldError::field)
                            .containsExactly("skippedFaces.SCR1");
                });
    }

    // [상황] 부스 세트에서 필수 면인 키보드를 건너뛰려 한다. 폰 세트에서도 키보드를 건너뛰려 한다.
    // [기대] 부스는 400(skippedFaces.KB)으로 거절하고, 폰은 키보드가 필수가 아니라 통과한다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("필수 면은 skippedFaces에 넣을 수 없다(400, skippedFaces.KB)")
    void requiredNotSkippable() {
        // 실행+확인: 부스에서 키보드를 건너뛰겠다고 하면 예외가 나야 하고
        assertThatThrownBy(() -> CompletionCheck.checkSkippable(CaptureSource.BOOTH, Map.of(Face.KB, "x")))
                // 확인: 틀린 칸은 skippedFaces.KB여야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errors())
                        .extracting(DomainException.FieldError::field).containsExactly("skippedFaces.KB"));
        // 실행+확인: 폰에서 키보드를 건너뛰는 것은 예외 없이 통과해야 한다
        CompletionCheck.checkSkippable(CaptureSource.PHONE, Map.of(Face.KB, "폰은 KB를 찍지 않음"));
    }

    // [상황] 올라간 사진 하나의 SHA-256이 처음 알린 값과 다르다(바뀌었거나 다른 파일).
    // [기대] 409 CAPTURE_HASH_MISMATCH로 완료를 거절한다(증거 사진 바꿔치기 방지).
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("올라간 사진의 해시가 알린 값과 다르면 409 CAPTURE_HASH_MISMATCH")
    void hashMismatch() {
        // 준비: 부스 필수 8면을 다 올린 상태를 만든다
        List<PhotoState> photos = allBooth(SHA);
        // 준비: 첫 사진을 "알린 지문과 다른 파일이 올라옴"으로 바꾼다
        photos.set(0, new PhotoState(Face.TOP, Light.L1, Camera.CAM1, SHA, OTHER_SHA));
        // 실행+확인: 완료 검사를 하면 예외가 나야 하고
        assertThatThrownBy(() -> CompletionCheck.check(CaptureSource.BOOTH, photos, Map.of()))
                // 확인: 오류 코드는 해시 불일치(409)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_HASH_MISMATCH));
    }

    // [상황] 세트를 완료한 뒤 한 번 더 완료하려 한다.
    // [기대] 처음엔 "올림 완료"(UPLOADED)가 되고 사진이 올라간 것으로 표시된다. 두 번째는 409 CAPTURE_SET_CLOSED로 거절한다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("완료된 세트는 다시 완료할 수 없다(409 CAPTURE_SET_CLOSED)")
    void completeOnce() {
        // 준비: 사진 한 장짜리 부스 세트를 연다
        CaptureSet set = CaptureSet.open("r", "c", CaptureSource.BOOTH, "b",
                List.of(spec(Face.TOP, Light.L1, Camera.CAM1)));
        // 실행: 그 사진이 올라왔다고 하고 세트를 완료한다
        set.complete(Set.of(set.getPhotos().get(0).getPublicId()), Map.of(), "key-0001", SHA);
        // 확인: 세트 상태는 올림 완료(UPLOADED)여야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        // (도구) isTrue() = 값이 참이어야 한다는 검사
        // 확인: 사진은 올라간 것으로 표시돼야 한다
        assertThat(set.getPhotos().get(0).isUploaded()).isTrue();
        // 실행+확인: 다른 요청 키로 한 번 더 완료하면 예외가 나야 하고
        assertThatThrownBy(() -> set.complete(Set.of(), Map.of(), "key-0002", SHA))
                // 확인: 오류 코드는 이미 닫힌 세트(409)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPTURE_SET_CLOSED));
    }

    // 테스트 도구: 부스 필수 8면을 CAM1·CAM2로 다 올린 상태를 만든다.
    private static List<PhotoState> allBooth(String actual) {
        // 빈 목록을 만든다
        List<PhotoState> photos = new ArrayList<>();
        // 부스 필수 8면마다
        for (Face face : FaceRules.required(CaptureSource.BOOTH)) {
            // CAM1 사진: 알린 지문 SHA, 실제 지문 actual
            photos.add(new PhotoState(face, Light.L1, Camera.CAM1, SHA, actual));
            // CAM2 사진도 같은 방식으로
            photos.add(new PhotoState(face, Light.L1, Camera.CAM2, SHA, actual));
        }
        // 완성된 16장 상태 목록을 돌려준다
        return photos;
    }

    // 테스트 도구: 사진 하나를 알리는 항목(면·조명·카메라, jpg, SHA-256, 촬영 시각)을 만든다.
    private static PhotoSpec spec(Face face, Light light, Camera camera) {
        // 받은 면·조명·카메라로 jpg 사진 항목을 만들어 돌려준다 (지문·시각은 고정값)
        return new PhotoSpec(face, light, camera, "jpg", SHA, OffsetDateTime.parse("2026-11-12T13:05:00+09:00"));
    }

    // 테스트 도구: 코드 목록을 이름 문자열 목록으로 바꾼다.
    private static List<String> names(Enum<?>[] values) {
        // 코드 하나하나의 이름만 뽑아 목록으로 돌려준다
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** contracts codes.yaml 0.6.0 error_code 중 이 서버가 쓰는 것을 포함한 전체 목록의 일부(공통 + capture). */
    // 테스트 도구: 계약 codes.yaml 0.6.0의 오류 코드 중 비교에 쓰는 목록(공통 + 촬영·검수).
    private static final Set<String> CONTRACT_ERROR_CODES = Set.of("VALIDATION_FAILED", "UNAUTHENTICATED", "FORBIDDEN",
            "OUT_OF_SCOPE", "NOT_FOUND", "RATE_LIMITED", "IDEMPOTENCY_KEY_CONFLICT", "INTERNAL_ERROR",
            "CAPTURE_FACES_MISSING", "CAPTURE_HASH_MISMATCH", "CAPTURE_SET_CLOSED", "REVIEW_PENDING_REMAINS",
            "REVIEW_NOT_READY", "REVIEW_CLOSED");
}
