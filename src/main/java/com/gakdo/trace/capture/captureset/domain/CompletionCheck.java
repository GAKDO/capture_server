package com.gakdo.trace.capture.captureset.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.DomainException.FieldError;
import com.gakdo.trace.capture.common.domain.ErrorCode;

/**
 * 세트 완료 판정. 저장소 확인 결과(사진마다 올라갔는지·해시가 맞는지)와 skippedFaces로 완료 가능 여부를 정한다.
 * 저장소·DB 없이 단위 테스트할 수 있게 순수 함수로 둔다.
 */
public final class CompletionCheck {

    /** 빠른 검사 필수 면마다 있어야 하는 카메라(contracts capture-server.yaml 세트 완료). */
    private static final Set<Camera> QUICK_CAMERAS = EnumSet.of(Camera.CAM1, Camera.CAM2);

    private CompletionCheck() {
    }

    /** 알린 사진 하나의 저장소 상태. actualSha256이 null이면 아직 올라가지 않았다. */
    public record PhotoState(Face face, Light light, Camera camera, String expectedSha256, String actualSha256) {

        public boolean present() {
            return actualSha256 != null;
        }

        public boolean hashMatches() {
            return expectedSha256.equals(actualSha256);
        }
    }

    /** 저장소를 보기 전에 할 수 있는 검사: 필수 면은 건너뛸 수 없다(400). */
    public static void checkSkippable(CaptureSource source, Map<Face, String> skipped) {
        checkSkippable(source, CaptureProfile.FULL, skipped);
    }

    /** 세트 범위(QUICK이면 위·아래 2면)까지 본 건너뛰기 검사. */
    public static void checkSkippable(CaptureSource source, CaptureProfile profile, Map<Face, String> skipped) {
        List<FieldError> errors = new ArrayList<>();
        for (Face face : skipped.keySet()) {
            if (FaceRules.required(source, profile).contains(face)) {
                errors.add(new FieldError("skippedFaces." + face,
                        sourceName(source) + " 촬영에서 " + face + "는 필수 면입니다"));
            }
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "필수 면은 건너뛸 수 없습니다.", errors);
        }
    }

    /**
     * 저장소 확인 뒤 검사. 순서: 해시 불일치(409) → 빠진 필수 면(409) → 사유 없이 올리지 않은 선택 면(400).
     * 필수 면은 그 면으로 알린 사진이 모두 올라가야 있는 것으로 본다(부스의 조명·카메라 조합이 하나라도 없으면 검사가 불완전).
     */
    public static void check(CaptureSource source, List<PhotoState> photos, Map<Face, String> skipped) {
        check(source, CaptureProfile.FULL, photos, skipped);
    }

    /** 세트 범위까지 본 완료 검사. QUICK은 codes.yaml capture.face_required_quick로 빠진 면을 센다. */
    public static void check(CaptureSource source, CaptureProfile profile, List<PhotoState> photos,
            Map<Face, String> skipped) {
        checkHashes(photos);
        Map<Face, Boolean> complete = completeness(photos);
        checkRequiredFaces(source, profile, photos, complete);
        checkSkipReasons(complete, skipped);
    }

    /** 올라간 사진의 해시가 알린 값과 다르면 409. */
    private static void checkHashes(List<PhotoState> photos) {
        List<String> mismatched = photos.stream()
                .filter(p -> p.present() && !p.hashMatches())
                .map(p -> p.face() + "-" + p.light() + "-" + p.camera())
                .toList();
        if (!mismatched.isEmpty()) {
            throw new DomainException(ErrorCode.CAPTURE_HASH_MISMATCH,
                    String.join(", ", mismatched) + " 사진을 다시 올려 주세요.");
        }
    }

    /** 면마다 알린 사진이 모두 올라갔는지. */
    private static Map<Face, Boolean> completeness(List<PhotoState> photos) {
        Map<Face, Boolean> complete = new EnumMap<>(Face.class);
        for (PhotoState p : photos) {
            complete.merge(p.face(), p.present(), Boolean::logicalAnd);
        }
        return complete;
    }

    /** 빠진 필수 면이 있으면 409. */
    private static void checkRequiredFaces(CaptureSource source, CaptureProfile profile, List<PhotoState> photos,
            Map<Face, Boolean> complete) {
        Set<Face> missing = EnumSet.noneOf(Face.class);
        for (Face face : FaceRules.required(source, profile)) {
            if (!Boolean.TRUE.equals(complete.get(face)) || isHalfQuickFace(profile, photos, face)) {
                missing.add(face);
            }
        }
        if (!missing.isEmpty()) {
            List<Face> ordered = List.copyOf(missing); // EnumSet은 codes.yaml 면 순서
            throw new DomainException(ErrorCode.CAPTURE_FACES_MISSING,
                    String.join(", ", ordered.stream().map(Face::name).toList()) + " 면을 올려 주세요.",
                    List.of(), Map.of("missingFaces", ordered));
        }
    }

    /**
     * QUICK에서 두 카메라가 면의 앞·뒤 반을 나눠 찍으므로, 한 카메라만 올라온 면은 반만 검사한 것이라 빠진 면으로 본다.
     */
    private static boolean isHalfQuickFace(CaptureProfile profile, List<PhotoState> photos, Face face) {
        if (profile != CaptureProfile.QUICK) {
            return false;
        }
        Set<Camera> cams = EnumSet.noneOf(Camera.class);
        photos.stream().filter(p -> p.face() == face && p.present()).forEach(p -> cams.add(p.camera()));
        return !cams.containsAll(QUICK_CAMERAS);
    }

    /** 올리지 않은 선택 면에 사유가 없으면 400. */
    private static void checkSkipReasons(Map<Face, Boolean> complete, Map<Face, String> skipped) {
        List<FieldError> unexplained = new ArrayList<>();
        complete.forEach((face, ok) -> {
            if (Boolean.FALSE.equals(ok) && !skipped.containsKey(face)) {
                unexplained.add(new FieldError("skippedFaces." + face, "올리지 않은 선택 면은 사유를 적어 주세요"));
            }
        });
        if (!unexplained.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "올리지 않은 선택 면의 사유가 없습니다.", unexplained);
        }
    }

    private static String sourceName(CaptureSource source) {
        return source == CaptureSource.BOOTH ? "부스" : "폰";
    }
}
