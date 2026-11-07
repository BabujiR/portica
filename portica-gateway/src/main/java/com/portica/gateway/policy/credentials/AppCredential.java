package com.portica.gateway.policy.credentials;

import java.util.List;
import java.util.Map;

/** The app behind a verified credential. */
public record AppCredential(String clientId, String appName, String developerEmail, List<String> apiProducts,
                            Map<String, String> attributes) {

    public AppCredential {
        apiProducts = apiProducts == null ? List.of() : List.copyOf(apiProducts);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
