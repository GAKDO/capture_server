// 이 파일이 확인하는 것: 관리자 화면 "속도·성능" 표(벤치마크). 촬영 세트마다 사진 수, 탐지 시간,
// 부스 전체 시간, 촬영 완료 → 등급 나오기까지 걸린 시간을 보여 준다.

package com.gakdo.trace.capture.benchmark.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.benchmark.application.BenchmarkService.Benchmark;
import com.gakdo.trace.capture.benchmark.infra.BenchmarkRepository;
import com.gakdo.trace.capture.benchmark.infra.BenchmarkRepository.BenchmarkRow;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.infra.Caller;

class BenchmarkServiceTest {

    // [상황] 촬영 완료 시각과 등급 시각으로 걸린 시간을 계산한다(서로 다른 시간대 표기 포함). 한쪽이 없거나 순서가 뒤집힌 경우도 넣는다.
    // [기대] 둘 다 있으면 그 사이 밀리초(32초)를 주고, 하나라도 없거나 등급이 완료보다 앞서면 값 없음(null)이다.
    @Test
    @DisplayName("완료와 등급 시각이 다 있으면 그 사이 ms를, 하나라도 없거나 순서가 뒤집히면 null을 준다")
    void completedToGraded() {
        OffsetDateTime done = OffsetDateTime.parse("2026-11-09T14:03:05+09:00");
        assertThat(BenchmarkService.millisBetween(done, OffsetDateTime.parse("2026-11-09T05:03:37Z")))
                .isEqualTo(32_000L);
        assertThat(BenchmarkService.millisBetween(null, done)).isNull();
        assertThat(BenchmarkService.millisBetween(done, null)).isNull();
        assertThat(BenchmarkService.millisBetween(done, done.minusSeconds(1))).isNull();
    }

    // [상황] DB에서 읽은 빠른 검사 세트 한 줄(사진 12장, 탐지 28.4초, 부스 전체 91.5초)을 화면용 모양으로 바꾼다.
    // [기대] 숫자가 그대로 옮겨지고, 탐지 결과 받은 시각이 없으면 비워 두며, 시각은 UTC로 낸다.
    @Test
    @DisplayName("세트 행을 계약 모양으로 옮기고 시각은 UTC 오프셋으로 낸다")
    void mapsRow() {
        BenchmarkRow row = new BenchmarkRow("U4aNk9bKxZfIZRBgSN2Uj", "h40b9AN9xMQhXRHrOyJWB", "BOOTH", "QUICK",
                "booth-01", "yolo11s-seg-v001-trt-fp16", 12, 28_400, OffsetDateTime.parse("2026-11-09T14:02:10+09:00"),
                OffsetDateTime.parse("2026-11-09T14:03:05+09:00"), null,
                OffsetDateTime.parse("2026-11-09T14:03:37+09:00"), 91_500);

        Benchmark b = BenchmarkService.toBenchmark(row);

        assertThat(b.photoCount()).isEqualTo(12);
        assertThat(b.inferenceMs()).isEqualTo(28_400);
        assertThat(b.boothTotalMs()).isEqualTo(91_500);
        assertThat(b.detectionsReceivedAt()).isNull();
        assertThat(b.completedToGradedMs()).isEqualTo(32_000L);
        assertThat(b.gradedAt()).hasToString("2026-11-09T05:03:37Z");
    }

    // [상황] 부스 토큰으로 벤치마크 목록을 요청한다.
    // [기대] 거절(403)하고 DB는 아예 읽지 않는다.
    @Test
    @DisplayName("관리자가 아니면 403이고 DB를 읽지 않는다")
    void adminOnly() {
        BenchmarkRepository repository = mock(BenchmarkRepository.class);
        Caller booth = new Caller("BOOTH", "booth-01", null, List.of());
        BenchmarkService service = new BenchmarkService(repository);
        assertThatThrownBy(() -> service.recent(booth, null, 20))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(repository);
    }
}
