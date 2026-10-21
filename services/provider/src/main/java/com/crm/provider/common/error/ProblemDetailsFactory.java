package com.crm.provider.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

public final class ProblemDetailsFactory {
    public static final String MDC_CORRELATION_ID = "correlationId";

    private ProblemDetailsFactory() {
    }

    public static ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
        return enrich(problem, code, request);
    }

    public static ProblemDetail enrich(ProblemDetail problem, ErrorCode code, HttpServletRequest request) {
        problem.setType(URI.create("https://crm.example/errors/"
                + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("correlationId", MDC.get(MDC_CORRELATION_ID));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
