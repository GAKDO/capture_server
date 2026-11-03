package com.gakdo.trace.capture.support;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.gakdo.trace.capture.captureset.application.PhotoStorage;

/** 메모리 저장소. put으로 "올라간" 사진(키 → SHA-256)을 흉내 낸다. */
public class FakePhotoStorage implements PhotoStorage {

    private final Map<String, String> objects = new ConcurrentHashMap<>();
    private final AtomicLong issued = new AtomicLong();

    public void put(String key, String sha256) {
        objects.put(key, sha256);
    }

    public void clear() {
        objects.clear();
    }

    @Override
    public UploadUrl presignPut(String objectKey, String contentType, String sha256Hex, Duration ttl) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("x-amz-checksum-sha256",
                Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)));
        return new UploadUrl("https://minio.test/trace-captures/" + objectKey + "?X-Amz-Signature=fake"
                + issued.incrementAndGet(), headers,
                OffsetDateTime.now(ZoneOffset.UTC).plus(ttl));
    }

    @Override
    public ViewUrl presignDerivedGet(String objectKey, Duration ttl) {
        return new ViewUrl("https://minio.test/trace-derived/" + objectKey + "?X-Amz-Signature=fake"
                + issued.incrementAndGet(), OffsetDateTime.now(ZoneOffset.UTC).plus(ttl));
    }

    @Override
    public ViewUrl presignCaptureGet(String objectKey, Duration ttl) {
        return new ViewUrl("https://minio.test/trace-captures/" + objectKey + "?X-Amz-Signature=fake"
                + issued.incrementAndGet(), OffsetDateTime.now(ZoneOffset.UTC).plus(ttl));
    }

    @Override
    public Optional<String> sha256Of(String objectKey) {
        return Optional.ofNullable(objects.get(objectKey));
    }

    @Override
    public boolean exists(String objectKey) {
        return objects.containsKey(objectKey);
    }
}
