package com.gakdo.trace.capture.captureset.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/** 사진 저장소(MinIO) 포트. 서버는 사진 본문을 받지 않고 사전 서명 URL만 만든다. 테스트에서는 가짜로 바꿔 끼운다. */
public interface PhotoStorage {

    /**
     * 사전 서명 PUT URL. headers는 클라이언트가 PUT에 그대로 실어야 하는 헤더(Content-Type, x-amz-checksum-sha256)로,
     * 서명에 들어가 있어 다른 내용·형식으로 올리면 저장소가 거절한다.
     */
    UploadUrl presignPut(String objectKey, String contentType, String sha256Hex, Duration ttl);

    /** 올라간 객체의 SHA-256(16진수 소문자). 객체가 없으면 빈 값. */
    Optional<String> sha256Of(String objectKey);

    /** 객체가 있는지(해시 확인 없이). */
    boolean exists(String objectKey);

    /** 버킷 trace-derived 객체(텍스처 등)의 사전 서명 GET URL. 3D 리포트가 직접 내려받는다(CA2 /media). */
    ViewUrl presignDerivedGet(String objectKey, Duration ttl);

    record ViewUrl(String url, OffsetDateTime expiresAt) {
    }

    record UploadUrl(String url, Map<String, String> headers, OffsetDateTime expiresAt) {
    }
}
