package com.gakdo.trace.capture.common.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.DomainException.FieldError;
import com.gakdo.trace.capture.common.domain.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/** 모든 오류를 한 곳에서 Problem Details(application/problem+json, code는 codes.yaml error_code)로 바꾼다. */
@RestControllerAdvice
public class ProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemFactory.of(e.code(), e.getMessage(), request.getRequestURI(), e.errors());
        e.properties().forEach(problem::setProperty);
        return ResponseEntity.status(e.code().status()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, null, request, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> invalidParameter(HandlerMethodValidationException e, HttpServletRequest request) {
        List<FieldError> errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new FieldError(r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, null, request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> violation(ConstraintViolationException e, HttpServletRequest request) {
        List<FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, null, request, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        List<FieldError> errors = List.of();
        Throwable cause = e.getCause();
        if (cause instanceof UnrecognizedPropertyException u) {
            errors = List.of(new FieldError(u.getPropertyName(), "받지 않는 필드입니다"));
        } else if (cause instanceof InvalidFormatException f) {
            errors = List.of(new FieldError(path(f), "값의 형식이 올바르지 않습니다"));
        } else if (cause instanceof MismatchedInputException m && !m.getPath().isEmpty()) {
            errors = List.of(new FieldError(path(m), "값의 형식이 올바르지 않습니다"));
        }
        return respond(ErrorCode.VALIDATION_FAILED, "요청 본문을 읽을 수 없습니다.", request, errors);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ProblemDetail> badParameter(Exception e, HttpServletRequest request) {
        List<FieldError> errors = switch (e) {
            case MethodArgumentTypeMismatchException m -> List.of(new FieldError(m.getName(), "값의 형식이 올바르지 않습니다"));
            case MissingRequestHeaderException h -> List.of(new FieldError(h.getHeaderName(), "필수 헤더입니다"));
            default -> List.of();
        };
        return respond(ErrorCode.VALIDATION_FAILED, null, request, errors);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ProblemDetail> notFound(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, null, request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e, HttpServletRequest request) {
        return respond(ErrorCode.FORBIDDEN, null, request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("처리하지 못한 오류: {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR, null, request, List.of());
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, HttpServletRequest request,
            List<FieldError> errors) {
        return ResponseEntity.status(code.status())
                .body(ProblemFactory.of(code, detail, request.getRequestURI(), errors));
    }

    private static String path(MismatchedInputException e) {
        StringBuilder sb = new StringBuilder();
        for (var ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
