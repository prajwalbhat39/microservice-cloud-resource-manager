package com.crm.quota.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * The ONLY place a problem body is constructed (NFR-10). A single construction point is what
 * makes "every error carries code/correlationId/timestamp" a property of the service rather than
 * a habit of whoever wrote a controller.
 */
public final class ProblemDetailsFactory {

    public static final String MDC_CORRELATION_ID = "correlationId";

    private ProblemDetailsFactory() {
    }

    /**
     * Build an RFC 9457 problem body extended with the three mandatory members (code,
     * correlationId, timestamp) plus any error-specific extensions.
     */
    public static ProblemDetail problem(ErrorCode code, int status, String detail,
                                        HttpServletRequest request, Map<String, Object> extensions) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(titleFor(code));
        pd.setDetail(detail);
        if (request != null) {
            pd.setInstance(URI.create(request.getRequestURI()));
        }
        pd.setProperty("code", code.name());
        pd.setProperty("correlationId", MDC.get(MDC_CORRELATION_ID));
        pd.setProperty("timestamp", Instant.now().toString());
        if (extensions != null) {
            extensions.forEach(pd::setProperty);
        }
        return pd;
    }

    /** Human-readable title derived from the code; a lookup table can replace this later. */
    private static String titleFor(ErrorCode code) {
        String words = code.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
