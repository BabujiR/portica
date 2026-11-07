package com.portica.common.policy.config;

import java.util.List;

/** A policy's type-specific settings. Defaults are applied in each record's constructor. */
public interface PolicyConfig {

    /** Human-readable problems; empty when the configuration is valid. */
    List<String> validate();
}
