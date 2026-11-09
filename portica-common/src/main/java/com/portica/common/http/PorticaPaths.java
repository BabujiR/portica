package com.portica.common.http;

/** Gateway paths Portica serves itself, below {@code /{org}/{env}}. API proxies cannot use them as base paths. */
public final class PorticaPaths {

    /** Reserved base path for the gateway's OAuth 2.0 endpoints. */
    public static final String OAUTH2 = "/oauth2";

    /** Client credentials token endpoint (RFC 6749 section 4.4). */
    public static final String TOKEN = OAUTH2 + "/token";

    /** Token revocation endpoint (RFC 7009). */
    public static final String REVOKE = OAUTH2 + "/revoke";

    /** Base path of AI proxies: {@code /{org}/{env}/ai/{name}/v1/...} (Phase 7). */
    public static final String AI = "/ai";

    /** Base path of MCP servers: {@code /{org}/{env}/mcp/{name}} (Phase 7). */
    public static final String MCP = "/mcp";

    private PorticaPaths() {
    }

    /** True when {@code basePath} is, or is inside, a reserved path. */
    public static boolean isReserved(String basePath) {
        if (basePath == null) {
            return false;
        }
        String p = basePath.toLowerCase(java.util.Locale.ROOT);
        for (String reserved : new String[]{OAUTH2, AI, MCP}) {
            if (p.equals(reserved) || p.startsWith(reserved + "/")) {
                return true;
            }
        }
        return false;
    }
}
