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
