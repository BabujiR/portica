package com.portica.gateway.policy.credentials;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** The gateway's {@link CredentialResolver}: developer apps from the management plane, plus development keys if any. */
@Configuration
public class CredentialsConfiguration {

    @Bean
    CredentialResolver credentialResolver(CredentialRegistry registry, DevCredentialsProperties devKeys) {
        List<CredentialResolver> resolvers = new ArrayList<>();
        resolvers.add(new ManagedCredentialResolver(registry));
        ConfiguredCredentialResolver configured = new ConfiguredCredentialResolver(devKeys);
        if (!configured.isEmpty()) {
            resolvers.add(configured);
        }
        return new CompositeCredentialResolver(resolvers);
    }
}
