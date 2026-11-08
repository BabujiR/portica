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

    /** Calling app, set by the gateway for AI and MCP services after it verified the credential (Phase 7). */
    public static final String APP = "X-Portica-App";

    /** Developer who owns the calling app (Phase 7). */
    public static final String DEVELOPER = "X-Portica-Developer";

    /** API product that authorized the call (Phase 7). */
    public static final String API_PRODUCT = "X-Portica-Api-Product";

    /**
     * Shared secret the gateway adds when forwarding to the AI and MCP services, so they only accept
     * calls that went through the gateway's policies (Phase 7).
     */
    public static final String INTERNAL_TOKEN = "X-Portica-Internal-Token";

    private PorticaHeaders() {
    }
}
