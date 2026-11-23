// 이 파일이 확인하는 것: 폰·브라우저에 돌려주는 사진 업로드/보기 주소(사전 서명 URL)의 공개 주소 바꾸기(MINIO_PUBLIC_BASE).
// MinIO는 내부 주소로 서명하지만, 사용자는 https://gakdo.site/ 같은 공개 주소로 접근해야 한다.
// 그래서 주소 앞부분만 공개 주소로 바꾸고, 뒤의 파일 경로와 서명 값은 한 글자도 바꾸지 않아야 한다(바뀌면 서명이 깨짐).

package com.gakdo.trace.capture.captureset.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.application.PhotoStorage.UploadUrl;

/** MINIO_PUBLIC_BASE: 폰에 돌려주는 사전 서명 URL의 앞부분만 공개 주소로 바뀌고 서명은 그대로 남는다. */
class S3PhotoStoragePublicUrlTest {

    private static final String SIGNED = "http://storage.java21.net:8000/trace-captures/sets/abc/TOP_L1_CAM1.jpg"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=key%2F20261102%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-SignedHeaders=content-type%3Bhost&X-Amz-Signature=deadbeef";

    // [상황] 공개 주소를 https://gakdo.site/ 로 정하고 내부 주소로 서명된 URL을 바꾼다.
    // [기대] 앞부분만 공개 주소로 바뀌고, 버킷·파일 경로와 ? 뒤 서명 값은 그대로다.
    @Test
    void 공개_주소가_있으면_앞부분만_바뀌고_경로와_서명_쿼리는_글자_그대로_남는다() {
        String url = S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase("https://gakdo.site/"));

        assertThat(url).isEqualTo("https://gakdo.site/trace-captures/sets/abc/TOP_L1_CAM1.jpg"
                + SIGNED.substring(SIGNED.indexOf('?')));
    }

    // [상황] 공개 주소에 포트와 경로(localhost:8315/s3)가 붙어 있다.
    // [기대] 그 경로 뒤에 버킷·파일 경로를 이어 붙인다.
    @Test
    void 공개_주소에_포트와_경로가_있으면_그_뒤에_붙인다() {
        String url = S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase("http://localhost:8315/s3"));

        assertThat(url).startsWith("http://localhost:8315/s3/trace-captures/sets/abc/TOP_L1_CAM1.jpg?X-Amz-Algorithm=");
    }

    // [상황] 공개 주소 설정이 비어 있거나 없다.
    // [기대] 서명된 URL을 바꾸지 않고 그대로 준다.
    @Test
    void 공개_주소가_비어_있으면_서명된_URL을_그대로_준다() {
        assertThat(S3PhotoStorage.normalizeBase("  ")).isNull();
        assertThat(S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase(null))).isEqualTo(SIGNED);
    }

    // [상황] 공개 주소를 "gakdo.site"(http 없음), "ftp://…", "https://사용자:비밀번호@…"처럼 잘못 적었다.
    // [기대] 서버가 시작할 때 바로 멈춘다(잘못된 주소를 사용자에게 내보내지 않음).
    @Test
    void 공개_주소가_http_s_주소가_아니면_시작할_때_멈춘다() {
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("gakdo.site"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("ftp://gakdo.site"))
                .isInstanceOf(IllegalStateException.class);
        // 사용자 정보는 폰에 돌려주는 URL에 그대로 실리므로 받지 않는다
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("https://user:pw@gakdo.site"))
                .isInstanceOf(IllegalStateException.class);
    }

    // [상황] 실제 사전 서명기(가짜 키)로 원본 사진(trace-captures) 업로드 URL을 만든다.
    // [기대] URL이 공개 주소로 시작하고 서명 값을 담으며, 내부 주소(minio:9000)는 드러나지 않는다.
    @Test
    void 사전_서명_URL은_공개_주소로_시작하고_버킷_경로와_서명_쿼리를_담는다() {
        StorageProperties props = new StorageProperties("http://minio:9000", "test-access", "test-secret", "us-east-1",
                "trace-captures", Duration.ofMinutes(10), "https://gakdo.site/", "trace-derived", Duration.ofMinutes(10));
        S3Config config = new S3Config();
        try (var s3 = config.s3Client(props); var presigner = config.s3Presigner(props)) {
            UploadUrl upload = new S3PhotoStorage(s3, presigner, props)
                    .presignPut("sets/abc/TOP_L1_CAM1.jpg", "image/jpeg", "ab".repeat(32), Duration.ofMinutes(10));

            assertThat(upload.url()).startsWith("https://gakdo.site/trace-captures/sets/abc/TOP_L1_CAM1.jpg?")
                    .contains("X-Amz-Signature=").doesNotContain("minio:9000");
        }
    }

    // [상황] 펼친 면 이미지(텍스처) 보기 URL을 만든다.
    // [기대] 가공 이미지 버킷(trace-derived)의 서명된 보기 주소이고, 공개 주소로 시작하며, 만료 시각이 있다.
    @Test
    void 텍스처_보기_URL은_trace_derived_버킷의_사전_서명_GET이고_공개_주소로_시작한다() {
        StorageProperties props = new StorageProperties("http://minio:9000", "test-access", "test-secret", "us-east-1",
                "trace-captures", Duration.ofMinutes(10), "https://gakdo.site/", "trace-derived", Duration.ofMinutes(10));
        S3Config config = new S3Config();
        try (var s3 = config.s3Client(props); var presigner = config.s3Presigner(props)) {
            var view = new S3PhotoStorage(s3, presigner, props)
                    .presignDerivedGet("textures/set1/TOP.jpg", Duration.ofMinutes(10));
            assertThat(view.url()).startsWith("https://gakdo.site/trace-derived/textures/set1/TOP.jpg?")
                    .contains("X-Amz-Signature=");
            assertThat(view.expiresAt()).isNotNull();
        }
    }
}
