package com.gakdo.trace.capture.common.domain;

import java.util.List;
import java.util.Map;

/** 업무 규칙 위반. ProblemAdvice 한 곳에서 Problem Details로 바뀐다. */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<FieldError> errors;
    private final transient Map<String, Object> properties;

    public DomainException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    public DomainException(ErrorCode code, String detail, List<FieldError> errors) {
        this(code, detail, errors, Map.of());
    }

    /** properties는 Problem에 그대로 더할 계약 칸(예: missingFaces). */
    public DomainException(ErrorCode code, String detail, List<FieldError> errors, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.errors = List.copyOf(errors);
        this.properties = Map.copyOf(properties);
    }

    public static DomainException notFound(String what) {
        return new DomainException(ErrorCode.NOT_FOUND, what + "을(를) 찾을 수 없습니다.");
    }

    public static DomainException invalid(String field, String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message, List.of(new FieldError(field, message)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    /** Problem의 errors[] 항목(계약 FieldError). */
    public record FieldError(String field, String message) {
    }
}
