package com.gakdo.trace.capture.captureset.infra;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.storage.*: MinIO 주소·키(infra.env MINIO_ENDPOINT, trace-secrets MINIO_ACCESS_KEY·SECRET_KEY)와 버킷.
 * derivedBucket(MINIO_DERIVED_BUCKET)은 inspect-worker가 올린 펼친 이미지·텍스처(trace-derived), viewTtl은 보기 URL 유효 시간(CA2 /media).
 * publicBase(MINIO_PUBLIC_BASE)는 폰·부스에 돌려주는 사전 서명 URL의 앞부분이다. 비어 있으면 endpoint 주소를 그대로 준다.
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String region,
        String captureBucket, Duration uploadTtl, String publicBase, String derivedBucket, Duration viewTtl) {
}
