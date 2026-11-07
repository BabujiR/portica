package com.portica.management.rbac;

/**
 * Organization-scoped permissions. Persisted by name in {@code role_permissions};
 * names must stay in sync with the Flyway seed migrations.
 * Permissions for later phases are defined now so built-in roles have stable semantics.
 */
public enum Permission {
    ORG_READ,
    ORG_UPDATE,

    ENV_READ,
    ENV_CREATE,
    ENV_UPDATE,
    ENV_DELETE,

    /** Create, update and delete target servers. Reading uses ENV_READ. */
    TARGET_SERVER_WRITE,

    MEMBER_READ,
    MEMBER_MANAGE,

    AUDIT_READ,

    PROXY_READ,
    PROXY_WRITE,
    PROXY_DEPLOY,

    PRODUCT_READ,
    PRODUCT_WRITE,

    DEVELOPER_READ,
    DEVELOPER_WRITE,

    ANALYTICS_READ,
    TRACE_RUN,

    AI_READ,
    AI_WRITE,

    MCP_READ,
    MCP_WRITE
}
