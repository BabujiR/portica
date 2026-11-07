package com.portica.gateway.policy.impl;

import com.portica.common.policy.config.JsonThreatProtectionConfig;
import com.portica.common.policy.config.RegexProtectionConfig;
import com.portica.gateway.policy.PolicyFault;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.portica.gateway.policy.TestMessages.request;
import static com.portica.gateway.policy.TestMessages.run;
import static org.assertj.core.api.Assertions.assertThat;

class ThreatProtectionPoliciesTest {

    @Nested
    class RegexProtection {

        private final RegexProtectionPolicy policy = new RegexProtectionPolicy("rx", new RegexProtectionConfig(
                List.of("\\.\\./"), Map.of("*", List.of("(?i)\\bunion\\b.+\\bselect\\b")),
                Map.of("user-agent", List.of("(?i)sqlmap")), List.of("(?i)<script")));

        @Test
        void cleanRequestsPass() {
            assertThat(run(policy, request("POST", "/items?q=blue").header("User-Agent", "curl/8")
                    .body("{\"name\":\"ok\"}").build())).isNull();
        }

        @Test
        void blocksEncodedPathTraversal() {
            PolicyFault fault = run(policy, request("GET", "/%2e%2e/etc/passwd").build());
            assertThat(fault.faultName()).isEqualTo("ThreatDetected");
            assertThat(fault.status()).isEqualTo(400);
        }

        @Test
        void blocksQueryHeaderAndBodyMatches() {
            assertThat(run(policy, request("GET", "/?id=1%20UNION%20SELECT%20pw").build())).isNotNull();
            assertThat(run(policy, request("GET", "/").header("User-Agent", "sqlmap/1.7").build())).isNotNull();
            assertThat(run(policy, request("POST", "/").body("<SCRIPT>alert(1)</script>").build())).isNotNull();
            assertThat(policy.requiresRequestBody()).isTrue();
        }
    }

    @Nested
    class JsonThreatProtection {

        private final JsonThreatProtectionPolicy policy = new JsonThreatProtectionPolicy("jtp",
                new JsonThreatProtectionConfig(3, 3, 3, 10, 8, 200));

        private PolicyFault check(String json) {
            return run(policy, request("POST", "/").header("Content-Type", "application/json").body(json).build());
        }

        @Test
        void acceptsPayloadsWithinLimits() {
            assertThat(check("{\"a\":{\"b\":[1,2,3]},\"c\":\"short\"}")).isNull();
        }

        @Test
        void rejectsEachLimit() {
            assertThat(check("{\"a\":{\"b\":{\"c\":{}}}}").faultName()).isEqualTo("JsonThreatDetected"); // depth
            assertThat(check("{\"a\":1,\"b\":2,\"c\":3,\"d\":4}")).isNotNull();                     // entries
            assertThat(check("[1,2,3,4]")).isNotNull();                                            // array
            assertThat(check("{\"a\":\"0123456789X\"}")).isNotNull();                              // string
            assertThat(check("{\"longpropname\":1}")).isNotNull();                                 // name
            assertThat(check("{\"a\":\"" + "x".repeat(300) + "\"}")).isNotNull();                  // body size
            assertThat(check("{not json")).isNotNull();
        }

        @Test
        void ignoresNonJsonBodies() {
            assertThat(run(policy, request("POST", "/").header("Content-Type", "text/plain").body("[[[[[[").build()))
                    .isNull();
        }
    }
}
