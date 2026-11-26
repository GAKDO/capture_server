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
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("완료와 등급 시각이 다 있으면 그 사이 ms를, 하나라도 없거나 순서가 뒤집히면 null을 준다")
    void completedToGraded() {
        // 준비: 촬영 완료 시각을 한국 시간 11/9 14:03:05로 정한다
        OffsetDateTime done = OffsetDateTime.parse("2026-11-09T14:03:05+09:00");
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 실행: 완료 시각과 UTC로 적은 등급 시각(= 한국 14:03:37) 사이 시간을 구한다
        assertThat(BenchmarkService.millisBetween(done, OffsetDateTime.parse("2026-11-09T05:03:37Z")))
                // 확인: 32초 = 32,000밀리초여야 한다 (시간대 표기가 달라도 정확히 계산)
                .isEqualTo(32_000L);
        // 실행+확인: 완료 시각이 없으면 값 없음(null)이어야 한다
        assertThat(BenchmarkService.millisBetween(null, done)).isNull();
        // 실행+확인: 등급 시각이 없어도 null이어야 한다
        assertThat(BenchmarkService.millisBetween(done, null)).isNull();
        // 실행+확인: 등급이 완료보다 1초 앞서면(순서 뒤집힘) null이어야 한다
        assertThat(BenchmarkService.millisBetween(done, done.minusSeconds(1))).isNull();
    }

    // [상황] DB에서 읽은 빠른 검사 세트 한 줄(사진 12장, 탐지 28.4초, 부스 전체 91.5초)을 화면용 모양으로 바꾼다.
    // [기대] 숫자가 그대로 옮겨지고, 탐지 결과 받은 시각이 없으면 비워 두며, 시각은 UTC로 낸다.
    @Test
    @DisplayName("세트 행을 계약 모양으로 옮기고 시각은 UTC 오프셋으로 낸다")
    void mapsRow() {
        // 준비: DB에서 읽은 세트 한 줄을 만든다: 세트 ID·시점 ID·부스 촬영·빠른 검사·부스 ID·모델 버전·사진 12장·탐지 28.4초·시작 시각,
        // 완료 시각, 탐지 결과 받은 시각(없음), 등급 시각, 부스 전체 91.5초
        BenchmarkRow row = new BenchmarkRow("U4aNk9bKxZfIZRBgSN2Uj", "h40b9AN9xMQhXRHrOyJWB", "BOOTH", "QUICK",
                "booth-01", "yolo11s-seg-v001-trt-fp16", 12, 28_400, OffsetDateTime.parse("2026-11-09T14:02:10+09:00"),
                OffsetDateTime.parse("2026-11-09T14:03:05+09:00"), null,
                OffsetDateTime.parse("2026-11-09T14:03:37+09:00"), 91_500);

        // 실행: 그 줄을 화면용 벤치마크 모양으로 바꾼다
        Benchmark b = BenchmarkService.toBenchmark(row);

        // 확인: 사진 수 12가 그대로 옮겨져야 한다
        assertThat(b.photoCount()).isEqualTo(12);
        // 확인: 탐지 시간 28,400ms가 그대로여야 한다
        assertThat(b.inferenceMs()).isEqualTo(28_400);
        // 확인: 부스 전체 시간 91,500ms가 그대로여야 한다
        assertThat(b.boothTotalMs()).isEqualTo(91_500);
        // 확인: 탐지 결과 받은 시각이 없으니 비어 있어야 한다
        assertThat(b.detectionsReceivedAt()).isNull();
        // 확인: 완료 → 등급 시간이 32초로 계산돼야 한다
        assertThat(b.completedToGradedMs()).isEqualTo(32_000L);
        // 확인: 등급 시각은 UTC 표기(…Z)로 나가야 한다 (hasToString = 글자로 바꾼 모양 비교)
        assertThat(b.gradedAt()).hasToString("2026-11-09T05:03:37Z");
    }

    // [상황] 부스 토큰으로 벤치마크 목록을 요청한다.
    // [기대] 거절(403)하고 DB는 아예 읽지 않는다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("관리자가 아니면 403이고 DB를 읽지 않는다")
    void adminOnly() {
        // (도구) mock(클래스) = 진짜 DB 대신 쓰는 가짜 객체(목). 불린 기록을 남긴다
        // 준비: 벤치마크 저장소를 가짜로 만든다
        BenchmarkRepository repository = mock(BenchmarkRepository.class);
        // 준비: 요청한 사람을 부스(역할 BOOTH, ID booth-01)로 만든다
        Caller booth = new Caller("BOOTH", "booth-01", null, List.of());
        // 준비: 가짜 저장소로 벤치마크 서비스를 만든다
        BenchmarkService service = new BenchmarkService(repository);
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 부스가 최근 20개 목록을 달라고 하면 예외가 나야 한다
        assertThatThrownBy(() -> service.recent(booth, null, 20))
                // 확인: 예외 종류는 DomainException(여기선 403 권한 없음)이어야 한다
                .isInstanceOf(DomainException.class);
        // (도구) verifyNoInteractions(가짜) = 그 가짜가 한 번도 불리지 않았어야 한다는 검사
        // 확인: DB(저장소)는 아예 읽지 않았어야 한다 (권한 검사가 먼저)
        verifyNoInteractions(repository);
    }
}
