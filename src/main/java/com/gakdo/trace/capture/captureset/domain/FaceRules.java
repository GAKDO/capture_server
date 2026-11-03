package com.gakdo.trace.capture.captureset.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 출처별 촬영 규칙. 필수 면은 contracts codes.yaml 0.6.0 {@code capture.face_required}를 옮긴 값이고 FaceRulesTest가 원본과 대조한다.
 * 카메라·조명 묶음은 codes.yaml 주석(부스 L1~L4·CAM1·CAM2, 폰 ROOM·PHONE, SCR1은 OFF)을 따른다.
 */
public final class FaceRules {

    private static final Set<Face> BOOTH_REQUIRED = EnumSet.of(Face.TOP, Face.BOT, Face.SF, Face.SB, Face.SL, Face.SR,
            Face.KB, Face.SCR0);
    private static final Set<Face> PHONE_REQUIRED = EnumSet.of(Face.TOP, Face.BOT, Face.SF, Face.SB, Face.SL, Face.SR,
            Face.SCR0);

    /** codes.yaml capture.face_required_quick.BOOTH: 부스 빠른 검사 위·아래 2면 (11/3 MVP). */
    private static final Set<Face> BOOTH_QUICK_REQUIRED = EnumSet.of(Face.TOP, Face.BOT);

    private FaceRules() {
    }

    /** 세트 범위까지 본 필수 면. QUICK은 부스만 있다(폰 QUICK은 세트 생성에서 400). */
    public static Set<Face> required(CaptureSource source, CaptureProfile profile) {
        if (profile == CaptureProfile.QUICK) {
            if (source != CaptureSource.BOOTH) {
                throw new IllegalArgumentException("QUICK 세트는 부스만: " + source);
            }
            return BOOTH_QUICK_REQUIRED;
        }
        return required(source);
    }

    /** 세트 완료에 꼭 있어야 하는 면. */
    public static Set<Face> required(CaptureSource source) {
        return switch (source) {
            case BOOTH -> BOOTH_REQUIRED;
            case PHONE -> PHONE_REQUIRED;
        };
    }

    public static boolean allows(CaptureSource source, Camera camera) {
        return switch (source) {
            case BOOTH -> camera == Camera.CAM1 || camera == Camera.CAM2;
            case PHONE -> camera == Camera.PHONE;
        };
    }

    public static boolean allows(CaptureSource source, Light light) {
        return switch (source) {
            case BOOTH -> light != Light.ROOM;
            case PHONE -> light == Light.ROOM || light == Light.OFF;
        };
    }
}
