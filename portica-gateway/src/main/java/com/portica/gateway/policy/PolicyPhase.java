package com.portica.gateway.policy;

/** Where a step runs. FAULT is the error path (fault rules). */
public enum PolicyPhase {
    REQUEST,
    RESPONSE,
    FAULT
}
