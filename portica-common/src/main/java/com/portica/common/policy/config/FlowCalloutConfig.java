package com.portica.common.policy.config;

import com.portica.common.validation.ResourceName;

import java.util.List;

/** FlowCallout: runs a shared flow deployed in the same environment. */
public record FlowCalloutConfig(String sharedFlow) implements PolicyConfig {

    @Override
    public List<String> validate() {
        return new Problems()
                .notBlank(sharedFlow, "sharedFlow")
                .require(sharedFlow == null || sharedFlow.matches(ResourceName.PATTERN), "'sharedFlow' is not a valid name")
                .list();
    }
}
