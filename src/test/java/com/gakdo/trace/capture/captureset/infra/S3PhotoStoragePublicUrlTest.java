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

    // 내부 MinIO 주소로 서명된 업로드 URL 견본 (? 뒤가 서명 값)
    private static final String SIGNED = "http://storage.java21.net:8000/trace-captures/sets/abc/TOP_L1_CAM1.jpg"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=key%2F20261102%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-SignedHeaders=content-type%3Bhost&X-Amz-Signature=deadbeef";

    // [상황] 공개 주소를 https://gakdo.site/ 로 정하고 내부 주소로 서명된 URL을 바꾼다.
    // [기대] 앞부분만 공개 주소로 바뀌고, 버킷·파일 경로와 ? 뒤 서명 값은 그대로다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시 (메서드 이름이 한글 설명을 대신함)
    @Test
    void 공개_주소가_있으면_앞부분만_바뀌고_경로와_서명_쿼리는_글자_그대로_남는다() {
        // 준비+실행: 공개 주소 https://gakdo.site/ 를 정리(normalizeBase)한 뒤 서명된 URL의 앞부분을 바꾼다
        String url = S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase("https://gakdo.site/"));

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 결과가 "공개 주소 + 원래 버킷·파일 경로 + 원래 ? 뒤 서명 값"과 글자 하나까지 같아야 한다
        assertThat(url).isEqualTo("https://gakdo.site/trace-captures/sets/abc/TOP_L1_CAM1.jpg"
                + SIGNED.substring(SIGNED.indexOf('?')));
    }

    // [상황] 공개 주소에 포트와 경로(localhost:8315/s3)가 붙어 있다.
    // [기대] 그 경로 뒤에 버킷·파일 경로를 이어 붙인다.
    @Test
    void 공개_주소에_포트와_경로가_있으면_그_뒤에_붙인다() {
        // 준비+실행: 포트·경로가 붙은 공개 주소로 앞부분을 바꾼다
        String url = S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase("http://localhost:8315/s3"));

        // 확인: 결과가 "localhost:8315/s3/" 뒤에 버킷·파일 경로와 서명 값이 이어진 모양으로 시작해야 한다 (startsWith = 앞부분 비교)
        assertThat(url).startsWith("http://localhost:8315/s3/trace-captures/sets/abc/TOP_L1_CAM1.jpg?X-Amz-Algorithm=");
    }

    // [상황] 공개 주소 설정이 비어 있거나 없다.
    // [기대] 서명된 URL을 바꾸지 않고 그대로 준다.
    @Test
    void 공개_주소가_비어_있으면_서명된_URL을_그대로_준다() {
        // 실행+확인: 공백뿐인 공개 주소 설정은 "없음(null)"으로 정리돼야 한다
        assertThat(S3PhotoStorage.normalizeBase("  ")).isNull();
        // 실행+확인: 공개 주소가 없으면 서명된 URL이 그대로 나와야 한다
        assertThat(S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase(null))).isEqualTo(SIGNED);
    }

    // [상황] 공개 주소를 "gakdo.site"(http 없음), "ftp://…", "https://사용자:비밀번호@…"처럼 잘못 적었다.
    // [기대] 서버가 시작할 때 바로 멈춘다(잘못된 주소를 사용자에게 내보내지 않음).
    @Test
    void 공개_주소가_http_s_주소가_아니면_시작할_때_멈춘다() {
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: http(s)가 빠진 주소는 예외가 나야 한다
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("gakdo.site"))
                // 확인: 예외 종류가 IllegalStateException(서버 시작을 멈추는 오류)이어야 한다
                .isInstanceOf(IllegalStateException.class);
        // 실행+확인: ftp 주소도 예외가 나야 한다
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("ftp://gakdo.site"))
                // 확인: 예외 종류가 IllegalStateException이어야 한다
                .isInstanceOf(IllegalStateException.class);
        // 사용자 정보는 폰에 돌려주는 URL에 그대로 실리므로 받지 않는다
        // 실행+확인: 사용자·비밀번호가 들어간 주소도 예외가 나야 한다
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("https://user:pw@gakdo.site"))
                // 확인: 예외 종류가 IllegalStateException이어야 한다
                .isInstanceOf(IllegalStateException.class);
    }

    // [상황] 실제 사전 서명기(가짜 키)로 원본 사진(trace-captures) 업로드 URL을 만든다.
    // [기대] URL이 공개 주소로 시작하고 서명 값을 담으며, 내부 주소(minio:9000)는 드러나지 않는다.
    @Test
    void 사전_서명_URL은_공개_주소로_시작하고_버킷_경로와_서명_쿼리를_담는다() {
        // 준비: 저장소 설정을 만든다: 내부 주소 minio:9000, 가짜 키, 지역, 원본 버킷, 유효 10분, 공개 주소, 가공 이미지 버킷, 유효 10분
        StorageProperties props = new StorageProperties("http://minio:9000", "test-access", "test-secret", "us-east-1",
                "trace-captures", Duration.ofMinutes(10), "https://gakdo.site/", "trace-derived", Duration.ofMinutes(10));
        // 준비: 운영 코드의 S3 연결 설정 객체를 만든다
        S3Config config = new S3Config();
        // 준비: 그 설정으로 S3 클라이언트와 사전 서명기를 만든다 (try(...) = 끝나면 자동으로 닫음)
        try (var s3 = config.s3Client(props); var presigner = config.s3Presigner(props)) {
            // 실행: 원본 사진 저장소로 JPEG 업로드 URL을 실제로 서명해 만든다 (SHA-256은 "ab"×32, 네트워크 없이 서명만 함)
            UploadUrl upload = new S3PhotoStorage(s3, presigner, props)
                    .presignPut("sets/abc/TOP_L1_CAM1.jpg", "image/jpeg", "ab".repeat(32), Duration.ofMinutes(10));

            // 확인: URL이 공개 주소 + 버킷·파일 경로로 시작하고
            assertThat(upload.url()).startsWith("https://gakdo.site/trace-captures/sets/abc/TOP_L1_CAM1.jpg?")
                    // 서명 값을 담되 내부 주소 minio:9000은 드러나지 않아야 한다
                    .contains("X-Amz-Signature=").doesNotContain("minio:9000");
        }
    }

    // [상황] 펼친 면 이미지(텍스처) 보기 URL을 만든다.
    // [기대] 가공 이미지 버킷(trace-derived)의 서명된 보기 주소이고, 공개 주소로 시작하며, 만료 시각이 있다.
    @Test
    void 텍스처_보기_URL은_trace_derived_버킷의_사전_서명_GET이고_공개_주소로_시작한다() {
        // 준비: 같은 저장소 설정을 만든다
        StorageProperties props = new StorageProperties("http://minio:9000", "test-access", "test-secret", "us-east-1",
                "trace-captures", Duration.ofMinutes(10), "https://gakdo.site/", "trace-derived", Duration.ofMinutes(10));
        // 준비: S3 연결 설정 객체를 만든다
        S3Config config = new S3Config();
        // 준비: S3 클라이언트와 사전 서명기를 만든다
        try (var s3 = config.s3Client(props); var presigner = config.s3Presigner(props)) {
            // 실행: 가공 이미지(펼친 면 텍스처) 보기용 URL을 서명해 만든다
            var view = new S3PhotoStorage(s3, presigner, props)
                    .presignDerivedGet("textures/set1/TOP.jpg", Duration.ofMinutes(10));
            // 확인: URL이 공개 주소 + trace-derived 버킷 경로로 시작하고
            assertThat(view.url()).startsWith("https://gakdo.site/trace-derived/textures/set1/TOP.jpg?")
                    // 서명 값을 담아야 한다
                    .contains("X-Amz-Signature=");
            // 확인: 만료 시각이 채워져 있어야 한다
            assertThat(view.expiresAt()).isNotNull();
        }
    }
}
