package com.gakdo.trace.capture.benchmark.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 모델별 평가 점수(contracts #30 ModelMetric). 학습이 끝난 뒤 사람이 검증 점수를 설정(MODEL_METRICS)에 넣는다.
 * DB에 두지 않는 까닭: 모델이 몇 개뿐이고 학습 PC에서 재는 숫자라 서버가 만들 수 없다. 잘못 적은 항목은 건너뛰고 경고만 남긴다.
 */
@Component
public class ModelMetrics {

    private static final Logger log = LoggerFactory.getLogger(ModelMetrics.class);
    private static final int MAX_MODELS = 50;

    private final List<ModelMetric> metrics;

    public ModelMetrics(@Value("${app.benchmark.model-metrics:}") String config) {
        this.metrics = parse(config);
    }

    public List<ModelMetric> all() {
        return metrics;
    }

    /** "버전=0.71/0.48,버전2=-/0.4". 점수는 0~1, `-`는 아직 없음(null). */
    static List<ModelMetric> parse(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        List<ModelMetric> out = new ArrayList<>();
        List<String> items = java.util.Arrays.stream(config.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).toList();
        for (String item : items) {
            if (out.size() == MAX_MODELS) {
                break;
            }
            try {
                out.add(parseOne(item));
            } catch (IllegalArgumentException e) {
                log.warn("MODEL_METRICS 항목을 건너뜁니다: {} ({})", item, e.getMessage());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static ModelMetric parseOne(String item) {
        int eq = item.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("'버전=mAP50/mAP50-95' 형식이 아님");
        }
        String version = item.substring(0, eq).trim();
        if (version.length() > 64) {
            throw new IllegalArgumentException("모델 버전이 64자를 넘음");
        }
        String[] scores = item.substring(eq + 1).split("/", -1);
        if (scores.length != 2) {
            throw new IllegalArgumentException("점수는 두 개(mAP50/mAP50-95)");
        }
        return new ModelMetric(version, score(scores[0]), score(scores[1]));
    }

    private static Double score(String raw) {
        String value = raw.trim();
        if (value.equals("-") || value.isEmpty()) {
            return null;
        }
        double d;
        try {
            d = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자가 아님: " + value);
        }
        if (!(d >= 0 && d <= 1)) {
            throw new IllegalArgumentException("점수는 0~1: " + value);
        }
        return d;
    }

    public record ModelMetric(String modelVersion, Double map50, Double map50To95) {
    }
}
