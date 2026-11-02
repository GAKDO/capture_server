package com.gakdo.trace.capture.captureset.infra;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.storage.*: MinIO 주소·키(infra.env MINIO_ENDPOINT, trace-secrets MINIO_ACCESS_KEY·SECRET_KEY)와 버킷. */
@ConfigurationProperties("app.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String region,
        String captureBucket, Duration uploadTtl) {
}
