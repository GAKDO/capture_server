package com.gakdo.trace.capture.common.api;

import java.util.List;

import org.springframework.http.ProblemDetail;

import com.gakdo.trace.capture.common.domain.DomainException.FieldError;
import com.gakdo.trace.capture.common.domain.ErrorCode;

/** 계약 Problem(RFC 9457 + code·errors) 본문을 만든다. 컨트롤러 오류와 보안 필터 오류가 같이 쓴다. */
public final class ProblemFactory {

    private ProblemFactory() {
    }

    public static ProblemDetail of(ErrorCode code, String detail, String instance, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setTitle(code.title());
        if (detail != null && !detail.isBlank()) {
            problem.setDetail(detail);
        }
        if (instance != null) {
            problem.setInstance(java.net.URI.create(instance));
        }
        problem.setProperty("code", code.name());
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }
}
