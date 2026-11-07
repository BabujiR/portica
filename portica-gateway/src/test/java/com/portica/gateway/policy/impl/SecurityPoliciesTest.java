package com.portica.gateway.policy.impl;

import com.portica.common.policy.config.AccessControlConfig;
import com.portica.common.policy.config.VerifyApiKeyConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.credentials.ConfiguredCredentialResolver;
import com.portica.gateway.policy.credentials.DevCredentialsProperties;
import com.portica.gateway.policy.credentials.DevCredentialsProperties.DevApiKey;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.portica.gateway.policy.TestMessages.request;
import static com.portica.gateway.policy.TestMessages.run;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityPoliciesTest {

    @Nested
    class VerifyApiKey {

        private final ConfiguredCredentialResolver credentials = new ConfiguredCredentialResolver(
                new DevCredentialsProperties(List.of(
                        new DevApiKey("acme", "good-key", "mobile-app", "dev@example.com", List.of("gold")),
                        new DevApiKey("globex", "globex-key", "other-app", "x@example.com", List.of()))));

        private final VerifyApiKeyPolicy fromHeader = new VerifyApiKeyPolicy("verify-key", new VerifyApiKeyConfig(null), credentials);

        @Test
        void validKeyPublishesAppVariables() {
            MessageContext ctx = request("GET", "/1").header("x-api-key", "good-key").build();

            assertThat(run(fromHeader, ctx)).isNull();
            assertThat(ctx.variable("verifyapikey.verify-key.app.name")).isEqualTo("mobile-app");
            assertThat(ctx.variable("verifyapikey.verify-key.developer.email")).isEqualTo("dev@example.com");
            assertThat(ctx.variable("verifyapikey.verify-key.apiproduct.name")).isEqualTo("gold");
            assertThat(ctx.variable("verifyapikey.verify-key.client_id")).isEqualTo("good-key");
        }

        @Test
        void missingKeyIs401FailedToResolve() {
            PolicyFault fault = run(fromHeader, request("GET", "/1").build());
            assertThat(fault.faultName()).isEqualTo("FailedToResolveAPIKey");
            assertThat(fault.status()).isEqualTo(401);
        }

        @Test
        void unknownKeyOrKeyOfAnotherOrganizationIs401Invalid() {
            assertThat(run(fromHeader, request("GET", "/1").header("x-api-key", "nope").build()).faultName())
                    .isEqualTo("InvalidApiKey");
            assertThat(run(fromHeader, request("GET", "/1").header("x-api-key", "globex-key").build()).faultName())
                    .isEqualTo("InvalidApiKey");
        }

        @Test
        void keyCanComeFromAQueryParameter() {
            VerifyApiKeyPolicy fromQuery = new VerifyApiKeyPolicy("k", new VerifyApiKeyConfig("{request.queryparam.apikey}"),
                    credentials);
            assertThat(run(fromQuery, request("GET", "/1?apikey=good-key").build())).isNull();
        }
    }

    @Nested
    class AccessControl {

        @Test
        void denyWinsAndAllowListIsExclusive() {
            AccessControlPolicy policy = new AccessControlPolicy("ip",
                    new AccessControlConfig(List.of("10.0.0.0/8"), List.of("10.6.6.0/24"), null));

            assertThat(run(policy, request("GET", "/").clientIp("10.1.2.3").build())).isNull();
            assertThat(run(policy, request("GET", "/").clientIp("10.6.6.6").build()).faultName()).isEqualTo("AccessDenied");
            assertThat(run(policy, request("GET", "/").clientIp("192.168.1.1").build()).status()).isEqualTo(403);
            assertThat(run(policy, request("GET", "/").clientIp(null).build())).isNotNull();
        }

        @Test
        void denyOnlyAllowsEverythingElse() {
            AccessControlPolicy policy = new AccessControlPolicy("ip", new AccessControlConfig(null, List.of("::1"), null));
            assertThat(run(policy, request("GET", "/").clientIp("8.8.8.8").build())).isNull();
            assertThat(run(policy, request("GET", "/").clientIp("::1").build())).isNotNull();
        }
    }
}
