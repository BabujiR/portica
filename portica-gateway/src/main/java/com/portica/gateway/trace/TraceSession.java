package com.portica.gateway.trace;

import java.time.Instant;

/**
 * An active debug session from the management plane: capture up to {@code maxTransactions} calls to
 * one proxy in one environment, optionally only those with a header value or from a client IP.
 */
public record TraceSession(String id, String organization, String environment, String apiProxy, String filterHeader,
                           String filterValue, String filterClientIp, int maxTransactions, Instant expiresAt) {
}
