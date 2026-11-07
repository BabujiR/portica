package com.portica.management.developer;

/** Approval of one API product for one credential. Only APPROVED grants reach the gateway. */
public enum ProductGrantStatus {
    APPROVED,
    PENDING,
    REVOKED
}
