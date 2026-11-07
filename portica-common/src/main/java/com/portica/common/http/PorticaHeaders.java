package com.portica.common.http;

/**
 * Custom HTTP headers used across Portica components. All are prefixed with {@code X-Portica-}.
 */
public final class PorticaHeaders {

    /** Correlation id for a single request; echoed back on every response and written to logs. */
    public static final String REQUEST_ID = "X-Portica-Request-Id";

    /** Organization a runtime request was resolved to (set by the gateway). */
    public static final String ORGANIZATION = "X-Portica-Org";

    /** Environment a runtime request was resolved to (set by the gateway). */
    public static final String ENVIRONMENT = "X-Portica-Env";

    /** API proxy that handled a runtime request (sent to the backend by the gateway). */
    public static final String PROXY = "X-Portica-Proxy";

    /** Deployed revision of the API proxy that handled a runtime request. */
    public static final String REVISION = "X-Portica-Revision";

    /** Requests a live trace session capture for this call (Phase 6). */
    public static final String TRACE = "X-Portica-Trace";

    private PorticaHeaders() {
    }
}
