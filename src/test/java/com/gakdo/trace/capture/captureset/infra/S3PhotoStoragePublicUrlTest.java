package com.gakdo.trace.capture.captureset.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** MINIO_PUBLIC_BASE: 폰에 돌려주는 사전 서명 URL의 앞부분만 공개 주소로 바뀌고 서명은 그대로 남는다. */
class S3PhotoStoragePublicUrlTest {

    private static final String SIGNED = "http://storage.java21.net:8000/trace-captures/sets/abc/TOP_L1_CAM1.jpg"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=key%2F20261102%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-SignedHeaders=content-type%3Bhost&X-Amz-Signature=deadbeef";

    @Test
    void 공개_주소가_있으면_앞부분만_바뀌고_경로와_서명_쿼리는_글자_그대로_남는다() {
        String url = S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase("https://gakdo.site/"));

        assertThat(url).isEqualTo("https://gakdo.site/trace-captures/sets/abc/TOP_L1_CAM1.jpg"
                + SIGNED.substring(SIGNED.indexOf('?')));
    }

    @Test
    void 공개_주소에_포트와_경로가_있으면_그_뒤에_붙인다() {
        String url = S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase("http://localhost:8315/s3"));

        assertThat(url).startsWith("http://localhost:8315/s3/trace-captures/sets/abc/TOP_L1_CAM1.jpg?X-Amz-Algorithm=");
    }

    @Test
    void 공개_주소가_비어_있으면_서명된_URL을_그대로_준다() {
        assertThat(S3PhotoStorage.normalizeBase("  ")).isNull();
        assertThat(S3PhotoStorage.publicUrl(SIGNED, S3PhotoStorage.normalizeBase(null))).isEqualTo(SIGNED);
    }

    @Test
    void 공개_주소가_http_s_주소가_아니면_시작할_때_멈춘다() {
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("gakdo.site"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> S3PhotoStorage.normalizeBase("ftp://gakdo.site"))
                .isInstanceOf(IllegalStateException.class);
    }
}
