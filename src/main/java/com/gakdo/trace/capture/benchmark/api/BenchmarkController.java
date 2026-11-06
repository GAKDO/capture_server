package com.gakdo.trace.capture.benchmark.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.capture.benchmark.application.BenchmarkService;
import com.gakdo.trace.capture.benchmark.application.BenchmarkService.Benchmark;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.common.infra.Caller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 관리자 성능 그래프(11/6 C 요청): 세트별 탐지 시간·완료→등급 시간. */
@RestController
@Validated
public class BenchmarkController {

    private final BenchmarkService benchmarks;

    public BenchmarkController(BenchmarkService benchmarks) {
        this.benchmarks = benchmarks;
    }

    @GetMapping("/api/v1/captures/benchmarks")
    public BenchmarkList list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) CaptureSource source,
            @RequestParam(defaultValue = "" + BenchmarkService.DEFAULT_LIMIT)
            @Min(1) @Max(BenchmarkService.MAX_LIMIT) int limit) {
        return new BenchmarkList(benchmarks.recent(Caller.from(jwt), source, limit));
    }

    public record BenchmarkList(List<Benchmark> items) {
    }
}
