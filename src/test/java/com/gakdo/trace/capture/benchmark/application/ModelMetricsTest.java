package com.gakdo.trace.capture.benchmark.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.benchmark.application.ModelMetrics.ModelMetric;

class ModelMetricsTest {

    @Test
    @DisplayName("설정이 비면 모델 점수는 빈 목록이다")
    void empty() {
        assertThat(ModelMetrics.parse("")).isEmpty();
        assertThat(ModelMetrics.parse(null)).isEmpty();
        assertThat(new ModelMetrics("  ").all()).isEmpty();
    }

    @Test
    @DisplayName("버전=mAP50/mAP50-95를 쉼표로 여럿 읽고, -는 아직 없음(null)이다")
    void parses() {
        assertThat(ModelMetrics.parse("yolo11s-seg-v001-trt-fp16=0.71/0.48, v002=-/0.4"))
                .containsExactly(new ModelMetric("yolo11s-seg-v001-trt-fp16", 0.71, 0.48),
                        new ModelMetric("v002", null, 0.4));
    }

    @Test
    @DisplayName("형식이 틀리거나 0~1 밖의 점수인 항목은 건너뛰고 나머지는 살린다")
    void skipsBad() {
        assertThat(ModelMetrics.parse("bad,v1=1.2/0.3,v2=0.5,v3=abc/0.1,=0.1/0.1,ok=0.6/0.3"))
                .containsExactly(new ModelMetric("ok", 0.6, 0.3));
    }
}
