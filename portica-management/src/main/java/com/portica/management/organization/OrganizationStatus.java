package com.portica.management.organization;

public enum OrganizationStatus {
    ACTIVE,
    /** Soft-deleted: hidden from members and denied by authorization; the name stays reserved. */
    ARCHIVED
}
