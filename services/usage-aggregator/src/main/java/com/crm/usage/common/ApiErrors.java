package com.crm.usage.common;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 404 -> "ENDPOINT_NOT_FOUND";
            case 400 -> failure instanceof HttpMessageNotReadableException
                    ? "MALFORMED_REQUEST" : "VALIDATION_FAILED";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 500 -> "INTERNAL_ERROR";
            default -> "HTTP_" + status.value();
        };
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, "Request could not be processed");
        if (failure instanceof MethodArgumentNotValidException validation) {
            detail.setProperty("errors", validation.getBindingResult().getFieldErrors().stream()
                    .map(e -> Map.of("field", e.getField(), "message", e.getDefaultMessage())).toList());
        }
        if (failure instanceof HandlerMethodValidationException validation) {
            detail.setProperty("errors", validation.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream().map(error -> Map.of(
                            "field", java.util.Objects.requireNonNullElse(
                                    result.getMethodParameter().getParameterName(), "request"),
                            "message", java.util.Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value"))))
                    .toList());
        }
        enrich(detail, code, request);
        return ResponseEntity.status(status).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure, WebRequest request) {
        log.error("api.unexpected cause={}", failure.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request);
    }

    private ResponseEntity<Object> problem(HttpStatus status, String code, WebRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
        enrich(detail, code, request);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }

    private void enrich(ProblemDetail detail, String code, WebRequest request) {
        detail.setType(URI.create("https://crm.example/errors/" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        detail.setInstance(URI.create(((ServletWebRequest) request).getRequest().getRequestURI()));
        detail.setProperty("code", code);
        detail.setProperty("correlationId", MDC.get("correlationId"));
        detail.setProperty("timestamp", Instant.now());
    }
}
