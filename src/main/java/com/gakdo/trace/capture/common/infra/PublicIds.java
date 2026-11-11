package com.gakdo.trace.capture.common.infra;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 밖에 보이는 ID(publicId): NanoID 21자(URL에 안전한 A-Za-z0-9_-). DB 기본 키(bigint)는 순번이라 내보내지 않는다.
 * (decisions.md 11/2, 헌법 1.1.0, contracts PublicId)
 */
public final class PublicIds {

    /** 계약 PublicId 패턴 */
    public static final String PATTERN = "^[A-Za-z0-9_-]{21}$";

    private static final char[] ALPHABET =
            "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict".toCharArray();
    private static final int SIZE = 21;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private PublicIds() {
    }

    public static String next() {
        byte[] bytes = new byte[SIZE];
        RANDOM.nextBytes(bytes);
        char[] id = new char[SIZE];
        for (int i = 0; i < SIZE; i++) {
            id[i] = ALPHABET[bytes[i] & 63]; // 64글자라 마스크만으로 고르게 뽑힌다
        }
        return new String(id);
    }

    public static boolean isValid(String value) {
        return value != null && COMPILED.matcher(value).matches();
    }
}
