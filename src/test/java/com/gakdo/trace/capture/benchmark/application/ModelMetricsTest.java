// 이 파일이 확인하는 것: 관리자 화면 "속도·성능"에 보여 줄 탐지 모델 점수(mAP50, mAP50-95) 설정 읽기.
// 점수는 환경 변수에 "모델버전=mAP50/mAP50-95"를 쉼표로 이어 적어 두고, 서버가 읽어서 보여 준다.

package com.gakdo.trace.capture.benchmark.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.benchmark.application.ModelMetrics.ModelMetric;

class ModelMetricsTest {

    // [상황] 점수 설정이 비어 있다(빈 문자열·없음·공백).
    // [기대] 모델 점수는 빈 목록이다(오류 없이).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("설정이 비면 모델 점수는 빈 목록이다")
    void empty() {
        // (도구) assertThat(실제값).isEmpty() = "실제값이 비어 있어야 한다"는 검사. 아니면 테스트 실패
        // 실행+확인: 빈 문자열 설정을 읽으면 빈 목록이어야 한다
        assertThat(ModelMetrics.parse("")).isEmpty();
        // 실행+확인: 설정이 아예 없어도(null) 오류 없이 빈 목록이어야 한다
        assertThat(ModelMetrics.parse(null)).isEmpty();
        // 실행+확인: 공백뿐인 설정으로 만든 점수 묶음도 비어 있어야 한다
        assertThat(new ModelMetrics("  ").all()).isEmpty();
    }

    // [상황] 모델 두 개의 점수를 적었고, 하나는 mAP50을 "-"(아직 측정 안 함)로 적었다.
    // [기대] 둘 다 읽히고, "-"는 값 없음(null)으로 남는다.
    @Test
    @DisplayName("버전=mAP50/mAP50-95를 쉼표로 여럿 읽고, -는 아직 없음(null)이다")
    void parses() {
        // (도구) containsExactly(...) = 정확히 이 항목들이 이 순서로 들어 있어야 한다는 검사
        // 실행: 모델 두 개의 점수가 적힌 설정을 읽는다 (v002의 mAP50은 "-")
        assertThat(ModelMetrics.parse("yolo11s-seg-v001-trt-fp16=0.71/0.48, v002=-/0.4"))
                // 확인: 첫 모델은 0.71/0.48로,
                .containsExactly(new ModelMetric("yolo11s-seg-v001-trt-fp16", 0.71, 0.48),
                        // 둘째 모델은 mAP50 없음(null)/0.4로 읽혀야 한다
                        new ModelMetric("v002", null, 0.4));
    }

    // [상황] 형식이 틀린 항목, 0~1 밖 점수, 숫자가 아닌 값, 이름 없는 항목이 섞여 있다.
    // [기대] 잘못된 항목만 건너뛰고 올바른 항목(ok)은 살린다(설정 한 줄 실수로 전체가 안 보이지 않게).
    @Test
    @DisplayName("형식이 틀리거나 0~1 밖의 점수인 항목은 건너뛰고 나머지는 살린다")
    void skipsBad() {
        // 실행: 형식 틀림(bad)·1 넘는 점수(1.2)·값 하나뿐(v2)·숫자 아님(abc)·이름 없음·정상(ok)이 섞인 설정을 읽는다
        assertThat(ModelMetrics.parse("bad,v1=1.2/0.3,v2=0.5,v3=abc/0.1,=0.1/0.1,ok=0.6/0.3"))
                // 확인: 정상 항목 ok 하나만 남아야 한다 (잘못된 항목만 건너뜀)
                .containsExactly(new ModelMetric("ok", 0.6, 0.3));
    }
}
