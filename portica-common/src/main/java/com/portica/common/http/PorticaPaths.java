package com.portica.common.http;

/** Gateway paths Portica serves itself, below {@code /{org}/{env}}. API proxies cannot use them as base paths. */
public final class PorticaPaths {

    /** Reserved base path for the gateway's OAuth 2.0 endpoints. */
    public static final String OAUTH2 = "/oauth2";

    /** Client credentials token endpoint (RFC 6749 section 4.4). */
    public static final String TOKEN = OAUTH2 + "/token";

    /** Token revocation endpoint (RFC 7009). */
    public static final String REVOKE = OAUTH2 + "/revoke";

    private PorticaPaths() {
    }

    /** True when {@code basePath} is, or is inside, a reserved path. */
    public static boolean isReserved(String basePath) {
        if (basePath == null) {
            return false;
        }
        String p = basePath.toLowerCase(java.util.Locale.ROOT);
        return p.equals(OAUTH2) || p.startsWith(OAUTH2 + "/");
    }
}
