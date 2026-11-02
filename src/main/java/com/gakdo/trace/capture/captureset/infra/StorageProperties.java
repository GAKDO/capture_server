package com.gakdo.trace.capture.captureset.infra;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.storage.*: MinIO 주소·키(infra.env MINIO_ENDPOINT, trace-secrets MINIO_ACCESS_KEY·SECRET_KEY)와 버킷.
 * publicBase(MINIO_PUBLIC_BASE)는 폰·부스에 돌려주는 사전 서명 URL의 앞부분이다. 비어 있으면 endpoint 주소를 그대로 준다.
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String region,
        String captureBucket, Duration uploadTtl, String publicBase) {
}
