// 테스트 도구(테스트 자체는 아님): MinIO 대신 쓰는 메모리 사진 저장소.
// put으로 "사진이 올라갔다"(파일 키 → SHA-256)를 흉내 내고, 사전 서명 URL은 가짜 주소로 만들어 준다.

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

    // "올라간" 사진 목록: 파일 키 → SHA-256 값 (여러 스레드가 동시에 써도 안전한 지도)
    private final Map<String, String> objects = new ConcurrentHashMap<>();
    // 발급한 URL 개수. URL마다 끝 번호를 달리해 서로 다르게 만든다
    private final AtomicLong issued = new AtomicLong();

    // 테스트가 "이 키로 사진이 올라갔다"고 만들 때 부른다
    public void put(String key, String sha256) {
        // 키와 SHA-256 값을 저장한다
        objects.put(key, sha256);
    }

    // 테스트 사이에 저장소를 비울 때 부른다
    public void clear() {
        // 저장된 사진을 모두 지운다
        objects.clear();
    }

    // (도구) @Override = 부모(PhotoStorage)에 정해진 메서드를 여기서 직접 만든다는 표시
    @Override
    // 사진 올리기용 사전 서명 URL(잠깐만 쓸 수 있는 업로드 주소)을 가짜로 만든다
    public UploadUrl presignPut(String objectKey, String contentType, String sha256Hex, Duration ttl) {
        // 업로드할 때 함께 보내야 할 머리말 목록을 만든다
        Map<String, String> headers = new LinkedHashMap<>();
        // 파일 종류(예: image/jpeg)를 넣는다
        headers.put("Content-Type", contentType);
        // SHA-256 값을 Base64로 바꿔 넣는다 (MinIO가 파일이 맞는지 확인하는 머리말)
        headers.put("x-amz-checksum-sha256",
                Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)));
        // 가짜 업로드 주소(끝에 발급 번호), 머리말, 만료 시각(지금+유효 시간)을 돌려준다
        return new UploadUrl("https://minio.test/trace-captures/" + objectKey + "?X-Amz-Signature=fake"
                + issued.incrementAndGet(), headers,
                OffsetDateTime.now(ZoneOffset.UTC).plus(ttl));
    }

    @Override
    // 분석 결과 이미지(trace-derived 버킷) 보기용 가짜 주소를 만든다
    public ViewUrl presignDerivedGet(String objectKey, Duration ttl) {
        // 가짜 주소와 만료 시각을 돌려준다
        return new ViewUrl("https://minio.test/trace-derived/" + objectKey + "?X-Amz-Signature=fake"
                + issued.incrementAndGet(), OffsetDateTime.now(ZoneOffset.UTC).plus(ttl));
    }

    @Override
    // 원본 사진(trace-captures 버킷) 보기용 가짜 주소를 만든다
    public ViewUrl presignCaptureGet(String objectKey, Duration ttl) {
        // 가짜 주소와 만료 시각을 돌려준다
        return new ViewUrl("https://minio.test/trace-captures/" + objectKey + "?X-Amz-Signature=fake"
                + issued.incrementAndGet(), OffsetDateTime.now(ZoneOffset.UTC).plus(ttl));
    }

    @Override
    // 올라간 사진의 SHA-256을 묻는다
    public Optional<String> sha256Of(String objectKey) {
        // 있으면 그 값을, 없으면 빈 값을 돌려준다
        return Optional.ofNullable(objects.get(objectKey));
    }

    @Override
    // 원본 사진을 지운다
    public void deleteCapture(String objectKey) {
        // 목록에서 그 키를 뺀다
        objects.remove(objectKey);
    }

    @Override
    // 분석 결과 이미지를 지운다
    public void deleteDerived(String objectKey) {
        // 목록에서 그 키를 뺀다
        objects.remove(objectKey);
    }

    @Override
    // 그 키의 파일이 있는지 묻는다
    public boolean exists(String objectKey) {
        // 목록에 있으면 true
        return objects.containsKey(objectKey);
    }
}
