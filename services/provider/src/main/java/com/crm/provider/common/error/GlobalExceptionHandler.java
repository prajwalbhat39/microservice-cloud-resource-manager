package com.crm.provider.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProviderException.class)
    public ProblemDetail onProvider(ProviderException e, HttpServletRequest request) {
        log.warn("api.error code={} status={} path={}", e.code(), e.code().status(), request.getRequestURI());
        return ProblemDetailsFactory.problem(e.code(), e.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request fields are invalid");
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message",
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
                .toList());
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(status);
        ErrorCode code = switch (status.value()) {
            case 400 -> problem.getProperties() != null
                    && ErrorCode.VALIDATION_FAILED.name().equals(problem.getProperties().get("code"))
                    ? ErrorCode.VALIDATION_FAILED : ErrorCode.MALFORMED_REQUEST;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            default -> status.is4xxClientError() ? ErrorCode.MALFORMED_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
        log.warn("api.error code={} status={} path={}", code, status.value(), request.getDescription(false));
        ProblemDetailsFactory.enrich(problem, code, ((ServletWebRequest) request).getRequest());
        return super.createResponseEntity(problem, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e, HttpServletRequest request) {
        log.error("api.error.unexpected path={}", request.getRequestURI(), e);
        return ProblemDetailsFactory.problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request);
    }
}
