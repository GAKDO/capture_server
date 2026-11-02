package com.gakdo.trace.capture.captureset.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gakdo.trace.capture.captureset.application.PhotoStorage;
import com.gakdo.trace.capture.common.infra.Times;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * MinIO 버킷 trace-captures. 사전 서명에 SHA-256 체크섬을 넣어 알린 사진과 다른 파일은 저장소가 받지 않게 하고,
 * 완료 때는 저장소가 계산해 둔 체크섬을 읽는다. 체크섬이 없으면(헤더 없이 올린 객체) 내려받아 직접 계산한다.
 * 사전 서명 URL은 비밀과 같으므로 로그에 남기지 않는다.
 */
@Component
public class S3PhotoStorage implements PhotoStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String publicBase;
    private final String derivedBucket;

    public S3PhotoStorage(S3Client s3, S3Presigner presigner, StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = props.captureBucket();
        this.publicBase = normalizeBase(props.publicBase());
        this.derivedBucket = props.derivedBucket() == null || props.derivedBucket().isBlank() ? "trace-derived"
                : props.derivedBucket();
    }

    @Override
    public UploadUrl presignPut(String objectKey, String contentType, String sha256Hex, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .checksumSHA256(base64(sha256Hex))
                .build();
        PresignedPutObjectRequest signed = presigner.presignPutObject(r -> r.signatureDuration(ttl).putObjectRequest(put));
        Map<String, String> headers = new LinkedHashMap<>();
        signed.signedHeaders().forEach((name, values) -> {
            if (!"host".equalsIgnoreCase(name)) { // Host는 클라이언트가 URL에서 스스로 붙인다
                headers.put(canonical(name), String.join(",", values));
            }
        });
        return new UploadUrl(publicUrl(signed.url().toString(), publicBase), headers,
                Times.utc(signed.expiration().atOffset(ZoneOffset.UTC)));
    }

    @Override
    public ViewUrl presignDerivedGet(String objectKey, Duration ttl) {
        PresignedGetObjectRequest signed = presigner.presignGetObject(r -> r.signatureDuration(ttl)
                .getObjectRequest(g -> g.bucket(derivedBucket).key(objectKey)));
        return new ViewUrl(publicUrl(signed.url().toString(), publicBase),
                Times.utc(signed.expiration().atOffset(ZoneOffset.UTC)));
    }

    @Override
    public Optional<String> sha256Of(String objectKey) {
        Optional<HeadObjectResponse> head = head(objectKey);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        String checksum = head.get().checksumSHA256();
        if (checksum != null && !checksum.contains("-")) { // "-N"이 붙으면 멀티파트 합성 체크섬이라 원본 해시가 아니다
            return Optional.of(HexFormat.of().formatHex(Base64.getDecoder().decode(checksum)));
        }
        return Optional.of(download(objectKey));
    }

    @Override
    public boolean exists(String objectKey) {
        return head(objectKey).isPresent();
    }

    private Optional<HeadObjectResponse> head(String objectKey) {
        try {
            return Optional.of(s3.headObject(r -> r.bucket(bucket).key(objectKey).checksumMode(ChecksumMode.ENABLED)));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private String download(String objectKey) {
        MessageDigest digest = sha256();
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(r -> r.bucket(bucket).key(objectKey))) {
            digestAll(in, digest);
        } catch (IOException e) {
            throw new UncheckedIOException("사진을 읽지 못했습니다: " + objectKey, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void digestAll(InputStream in, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
    }

    /**
     * 사전 서명 URL의 scheme://host[:port]만 공개 주소로 바꾼다. 경로(/버킷/키)와 서명 쿼리는 글자 그대로 둔다.
     * 서명에는 MinIO 원래 Host가 들어 있으므로, 공개 주소의 프록시(web nginx)가 Host를 MinIO 주소로 넘겨야 검증이 맞는다.
     * 공개 주소에 경로가 있으면(예: https://example.com/s3) 그 뒤에 붙인다.
     */
    static String publicUrl(String signedUrl, String publicBase) {
        if (publicBase == null) {
            return signedUrl;
        }
        URI signed = URI.create(signedUrl);
        String query = signed.getRawQuery() == null ? "" : "?" + signed.getRawQuery();
        return publicBase + signed.getRawPath() + query;
    }

    /** 빈 값은 null(바꾸지 않음), 끝의 /는 뗀다. http(s)://호스트 형태가 아니면 시작할 때 멈춘다. */
    static String normalizeBase(String base) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String trimmed = base.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri = URI.create(trimmed);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("MINIO_PUBLIC_BASE는 사용자 정보 없이 http(s)://호스트[:포트][/경로] 형태여야 합니다"); // 값은 비밀이 섞였을 수 있어 싣지 않는다
        }
        return trimmed;
    }

    static String base64(String sha256Hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex));
    }

    /** 서명된 헤더 이름은 소문자로 온다. 계약 예시처럼 Content-Type은 보기 좋은 이름으로, x-amz-*는 그대로. */
    private static String canonical(String name) {
        return "content-type".equalsIgnoreCase(name) ? "Content-Type" : name;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
