package com.portica.gateway.policy;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;

/** Creates policies of one type. Register implementations as Spring beans. */
public interface PolicyFactory {

    PolicyType type();

    /**
     * @param name    the policy's name in the bundle
     * @param config  the bound and validated config record for {@link #type()}
     * @param context compile-time services (shared flow lookup, deployment identity)
     */
    Policy create(String name, PolicyConfig config, PolicyCompileContext context);
}
