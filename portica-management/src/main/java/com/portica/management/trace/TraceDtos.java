package com.portica.management.trace;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class TraceDtos {

    private TraceDtos() {
    }

    /**
     * Starts a debug session. Without filters every call to the proxy is captured, up to
     * {@code maxTransactions}.
     *
     * @param filterHeader    capture only calls carrying this header ...
     * @param filterValue     ... with this value (any value when empty)
     * @param filterClientIp  capture only calls from this client address
     * @param durationSeconds how long the session runs (default 300, at most 600)
     * @param maxTransactions how many calls to capture (default 20, at most 100)
     */
    public record StartTraceRequest(
            @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9!#$%&'*+.^_`|~-]*", message = "must be an HTTP header name")
            String filterHeader,
            @Size(max = 512) String filterValue,
            @Size(max = 64) @Pattern(regexp = "[0-9A-Fa-f.:]*", message = "must be an IP address") String filterClientIp,
            @Min(10) @Max(600) Integer durationSeconds,
            @Min(1) @Max(100) Integer maxTransactions) {
    }

    public enum TraceState { ACTIVE, EXPIRED, STOPPED }

    public record TraceSessionResponse(UUID id, String environment, String apiProxy, String filterHeader,
                                       String filterValue, String filterClientIp, int maxTransactions,
                                       TraceState state, String createdBy, Instant createdAt, Instant expiresAt,
                                       Instant stoppedAt) {
    }

    /** One captured call: summary columns plus the full payload (steps, target, response, variables). */
    public record TraceTransaction(String requestId, Instant time, int status, long totalMs,
                                   Map<String, Object> payload) {
    }

    /** What gateways poll: the sessions active right now. */
    public record RuntimeTraceSession(UUID id, String organization, String environment, String apiProxy,
                                      String filterHeader, String filterValue, String filterClientIp,
                                      int maxTransactions, Instant expiresAt) {
    }
}
