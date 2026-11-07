package com.portica.management.product;

/** How an app's request for an API product is approved. */
public enum ApprovalType {
    /** Credentials get the product approved immediately. */
    AUTO,
    /** Credentials get the product as PENDING until an administrator approves it. */
    MANUAL
}
