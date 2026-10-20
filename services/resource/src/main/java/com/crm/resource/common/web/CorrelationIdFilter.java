package com.crm.resource.common.web;

import com.crm.resource.common.error.ProblemDetailsFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * NFR-13: reads X-Correlation-Id or generates one, MDC for the life of the request, ALWAYS
 * cleared in finally (Tomcat reuses threads). This is the ORCHESTRATOR's copy; unlike Quota's,
 * this ID must ALSO survive the async saga boundary — see AsyncConfig's TaskDecorator and the
 * correlationId field carried on ProvisioningTask/TerminationTask (nfr-design.md 3.3).
 *
 * <p>Length cap (64) and blank check: the ID lands in every log line, so an unbounded
 * caller-supplied header would be a log-injection and log-bloat vector.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = ProblemDetailsFactory.MDC_CORRELATION_ID;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming != null && !incoming.isBlank() && incoming.length() <= 64)
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
