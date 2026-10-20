package com.crm.resource.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single advice for the service (NFR-10). Copied from Quota (NFR-5). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail onApi(ApiException e, HttpServletRequest req) {
        log.warn("api.error code={} status={} path={}", e.code(), e.code().status(), req.getRequestURI());
        return ProblemDetailsFactory.problem(e.code(), e.code().status(), e.getMessage(), req, e.extensions());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onBodyValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        // One errors[] entry per FieldError: { field, message, rejectedValue } (nfr-design.md 1.2).
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.add(errorEntry(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()));
        }
        // Object-level (class) constraints have no field; report them under their object name.
        for (ObjectError oe : e.getBindingResult().getGlobalErrors()) {
            errors.add(errorEntry(oe.getObjectName(), oe.getDefaultMessage(), null));
        }
        return validationProblem(errors, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onParamValidation(ConstraintViolationException e, HttpServletRequest req) {
        // One errors[] entry per violation; field = last node of the property path (nfr-design.md 1.2).
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            errors.add(errorEntry(lastPathNode(v.getPropertyPath()), v.getMessage(), v.getInvalidValue()));
        }
        return validationProblem(errors, req);
    }

    private static ProblemDetail validationProblem(List<Map<String, Object>> errors, HttpServletRequest req) {
        return ProblemDetailsFactory.problem(ErrorCode.VALIDATION_FAILED, 400,
                errors.size() + " field(s) are invalid", req, Map.of("errors", errors));
    }

    /**
     * Build one errors[] entry. rejectedValue is included only for scalars (String, Number,
     * Boolean, Enum); anything else is omitted so no structured/sensitive content is echoed (NFR-17).
     */
    private static Map<String, Object> errorEntry(String field, String message, Object rejectedValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("message", message);
        if (isScalar(rejectedValue)) {
            entry.put("rejectedValue", rejectedValue instanceof Enum<?> en ? en.name() : rejectedValue);
        }
        return entry;
    }

    private static boolean isScalar(Object v) {
        return v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Enum<?>;
    }

    private static String lastPathNode(Path path) {
        String last = null;
        for (Path.Node node : path) {
            last = node.getName();
        }
        return last;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail onMissingHeader(MissingRequestHeaderException e, HttpServletRequest req) {
        ErrorCode code = "Idempotency-Key".equalsIgnoreCase(e.getHeaderName())
                ? ErrorCode.IDEMPOTENCY_KEY_REQUIRED
                : ErrorCode.VALIDATION_FAILED;
        return ProblemDetailsFactory.problem(code, code.status(),
                "Required header missing: " + e.getHeaderName(), req, Map.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail onMissingParam(MissingServletRequestParameterException e, HttpServletRequest req) {
        return ProblemDetailsFactory.problem(ErrorCode.VALIDATION_FAILED, 400,
                "Required parameter missing: " + e.getParameterName(), req,
                Map.of("errors", List.of(Map.of("field", e.getParameterName(), "message", "must be provided"))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return ProblemDetailsFactory.problem(ErrorCode.MALFORMED_REQUEST, 400,
                "Request body could not be read", req, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail onNoResource(NoResourceFoundException e, HttpServletRequest req) {
        return ProblemDetailsFactory.problem(ErrorCode.RESOURCE_NOT_FOUND, 404,
                "No handler for " + req.getRequestURI(), req, Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail onMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return ProblemDetailsFactory.problem(ErrorCode.METHOD_NOT_ALLOWED, 405,
                "Method " + e.getMethod() + " not allowed", req, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e, HttpServletRequest req) {
        log.error("api.error.unexpected path={}", req.getRequestURI(), e);
        return ProblemDetailsFactory.problem(ErrorCode.INTERNAL_ERROR, 500,
                "An unexpected error occurred", req, Map.of());
    }
}
