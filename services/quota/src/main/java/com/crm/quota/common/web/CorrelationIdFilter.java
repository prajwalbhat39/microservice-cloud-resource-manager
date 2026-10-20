package com.crm.quota.common.web;

import com.crm.quota.common.error.ProblemDetailsFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * NFR-13: reads {@code X-Correlation-Id} or generates one, puts it in the SLF4J MDC for the life
 * of the request, echoes it on the response, and ALWAYS clears it in a finally block (MDC is
 * thread-local; leaving a value behind would leak it into the next request on the same thread).
 *
 * <p>The orchestrator copies this class and additionally forwards the id on outbound calls and
 * across the async saga boundary; Quota makes no outbound call, so it only needs the inbound leg.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(ProblemDetailsFactory.MDC_CORRELATION_ID, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ProblemDetailsFactory.MDC_CORRELATION_ID);
        }
    }
}
