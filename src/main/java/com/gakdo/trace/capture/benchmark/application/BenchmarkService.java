package com.gakdo.trace.capture.benchmark.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.benchmark.infra.BenchmarkRepository;
import com.gakdo.trace.capture.benchmark.infra.BenchmarkRepository.BenchmarkRow;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;

/** 세트별 처리 시간(성능 그래프, contracts GET /api/v1/captures/benchmarks). 관리자만. */
@Service
public class BenchmarkService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final BenchmarkRepository benchmarks;

    public BenchmarkService(BenchmarkRepository benchmarks) {
        this.benchmarks = benchmarks;
    }

    @Transactional(readOnly = true)
    public List<Benchmark> recent(Caller caller, CaptureSource source, int limit) {
        if (!caller.isAdmin()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "관리자만 성능 기록을 볼 수 있습니다.");
        }
        return benchmarks.findRecent(source == null ? null : source.name(), limit).stream()
                .map(BenchmarkService::toBenchmark)
                .toList();
    }

    static Benchmark toBenchmark(BenchmarkRow row) {
        return new Benchmark(row.captureSetId(), row.checkpointId(), row.source(), row.profile(), row.boothId(),
                row.modelVersion(), row.photoCount(), row.inferenceMs(), utc(row.createdAt()), utc(row.completedAt()),
                utc(row.detectionsReceivedAt()), utc(row.gradedAt()), millisBetween(row.completedAt(), row.gradedAt()));
    }

    /** 둘 중 하나라도 없거나 순서가 뒤집혔으면(재등급 등) null. */
    static Long millisBetween(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        return Duration.between(from, to).toMillis();
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    public record Benchmark(String captureSetId, String checkpointId, String source, String profile, String boothId,
            String modelVersion, int photoCount, Integer inferenceMs, OffsetDateTime createdAt,
            OffsetDateTime completedAt, OffsetDateTime detectionsReceivedAt, OffsetDateTime gradedAt,
            Long completedToGradedMs) {
    }
}
