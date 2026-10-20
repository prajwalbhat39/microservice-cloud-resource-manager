package com.crm.resource.client;

import com.crm.resource.common.error.DownstreamUnavailableException;
import com.crm.resource.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retry with exponential backoff and jitter (nfr-design.md 8.2), shared by the peer clients.
 *
 * <p>Retried: connect/read timeouts, connection refused ({@link ResourceAccessException}), and
 * HTTP <b>503</b>. NOT retried: any other 4xx or 5xx — a 422 is a final business answer and
 * retrying it would only delay the saga's compensation.
 *
 * <p>Jitter matters because Challenge 1 fires 20 simultaneous requests: without it, 20 retries
 * would re-collide in lockstep.
 *
 * <p>When every attempt fails, the call throws {@link DownstreamUnavailableException} with the
 * code the caller chose ({@code PROVIDER_UNAVAILABLE}, {@code BILLING_UNAVAILABLE}, …) and a
 * detail of the shape {@code "<what> failed after N attempts (<status>)"}, which is exactly the
 * {@code <detail> (<status>)} that {@code FailureReason.of} renders into failure_reason.
 */
@Component
public class PeerRetry {

    private static final Logger log = LoggerFactory.getLogger(PeerRetry.class);

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double multiplier;
    private final long maxBackoffMs;
    private final double jitterRatio;

    public PeerRetry(@Value("${retry.max-attempts:3}") int maxAttempts,
                     @Value("${retry.initial-backoff-ms:200}") long initialBackoffMs,
                     @Value("${retry.multiplier:2.0}") double multiplier,
                     @Value("${retry.max-backoff-ms:2000}") long maxBackoffMs,
                     @Value("${retry.jitter-ratio:0.5}") double jitterRatio) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
        this.maxBackoffMs = maxBackoffMs;
        this.jitterRatio = jitterRatio;
    }

    /**
     * Runs {@code call}, retrying only transient failures.
     *
     * @param code      the 503 code to report when every attempt fails
     * @param operation human-readable operation name for the detail string and the logs
     * @param retryOnReadTimeout {@code false} for the Quota client: a read timeout there is
     *                           ambiguous (the reservation may have been taken), so only connect
     *                           failures are retried (nfr-design.md 8.2)
     */
    public <T> T call(ErrorCode code, String operation, boolean retryOnReadTimeout, Supplier<T> call) {
        RuntimeException last = null;
        int status = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (HttpServerErrorException.ServiceUnavailable e) {
                last = e;
                status = 503;
            } catch (ResourceAccessException e) {
                // Connect refused / connect timeout / read timeout all surface here.
                if (!retryOnReadTimeout && isReadTimeout(e)) {
                    throw unavailable(code, operation, attempt, 0, e);
                }
                last = e;
                status = 0;
            } catch (RestClientException e) {
                throw e;   // any other status: a final answer, never retried
            }

            if (attempt < maxAttempts) {
                log.warn("peer.retry operation={} attempt={} of={} reason={}",
                        operation, attempt, maxAttempts, last.getClass().getSimpleName());
                sleep(backoffMs(attempt));
            }
        }
        throw unavailable(code, operation, maxAttempts, status, last);
    }

    public void callVoid(ErrorCode code, String operation, boolean retryOnReadTimeout, Runnable call) {
        call(code, operation, retryOnReadTimeout, () -> {
            call.run();
            return null;
        });
    }

    private DownstreamUnavailableException unavailable(ErrorCode code, String operation,
                                                       int attempts, int status, Throwable cause) {
        String detail = operation + " failed after " + attempts + (attempts == 1 ? " attempt (" : " attempts (")
                + (status > 0 ? status : "no response") + ")";
        log.error("peer.unavailable operation={} attempts={} status={}", operation, attempts, status, cause);
        return new DownstreamUnavailableException(code, detail);
    }

    /** Backoff for the delay AFTER attempt {@code attempt}, capped, then jittered by ±ratio. */
    private long backoffMs(int attempt) {
        double raw = initialBackoffMs * Math.pow(multiplier, attempt - 1.0);
        long capped = (long) Math.min(raw, maxBackoffMs);
        long jitter = (long) (capped * jitterRatio);
        if (jitter <= 0) {
            return capped;
        }
        return Math.max(0, capped - jitter + ThreadLocalRandom.current().nextLong(2 * jitter + 1));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }

    private static boolean isReadTimeout(ResourceAccessException e) {
        return e.getCause() instanceof java.net.SocketTimeoutException;
    }
}
