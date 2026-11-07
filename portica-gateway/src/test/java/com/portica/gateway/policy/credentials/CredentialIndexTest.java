package com.portica.gateway.policy.credentials;

import com.portica.gateway.policy.credentials.DevCredentialsProperties.DevApiKey;
import com.portica.gateway.runtime.RuntimeModel.RuntimeQuota;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.portica.gateway.policy.credentials.TestCredentials.credential;
import static com.portica.gateway.policy.credentials.TestCredentials.product;
import static com.portica.gateway.policy.credentials.TestCredentials.registry;
import static org.assertj.core.api.Assertions.assertThat;

class CredentialIndexTest {

    private final CredentialRegistry registry = registry(
            List.of(product("pets-read", List.of("pets"), List.of("/pets/**"), List.of("pets.read"), new RuntimeQuota(100, 1, "hour")),
                    product("orders", List.of("orders"), List.of(), List.of("orders.write"), null)),
            List.of(credential("key-1", "secret-1", "mobile", null, "pets-read", "orders", "not-offered-here")));

    @Test
    void findsCredentialsByKeyWithTheirOfferedProducts() {
        AppCredential app = registry.index("acme", "dev").findByKey("key-1").orElseThrow();

        assertThat(app.clientId()).isEqualTo("key-1");
        assertThat(app.appName()).isEqualTo("mobile");
        assertThat(app.productNames()).containsExactly("pets-read", "orders"); // unknown products dropped
        assertThat(app.scopes()).containsExactly("pets.read", "orders.write");
        assertThat(registry.index("acme", "dev").findByKey("key-2")).isEmpty();
        assertThat(registry.index("acme", "prod").findByKey("key-1")).isEmpty();
    }

    @Test
    void productsMatchProxiesAndPathPatterns() {
        AppCredential app = registry.index("acme", "dev").findByKey("key-1").orElseThrow();

        assertThat(app.productFor("pets", "/pets/42").map(ApiProductGrant::name)).hasValue("pets-read");
        assertThat(app.productFor("pets", "/admin")).isEmpty();
        assertThat(app.productFor("orders", "")).map(ApiProductGrant::name).hasValue("orders");
        assertThat(app.productFor("billing", "/")).isEmpty();
        assertThat(app.productFor("pets", "/pets/42").orElseThrow().quota().count()).isEqualTo(100);
    }

    @Test
    void expiryIsCheckedAgainstTheClock() {
        Instant soon = Instant.parse("2026-01-01T00:00:00Z");
        CredentialRegistry r = registry(List.of(), List.of(credential("k", "s", "a", soon)));
        AppCredential app = r.index("acme", "dev").findByKey("k").orElseThrow();

        assertThat(app.expired(soon.minusSeconds(1))).isFalse();
        assertThat(app.expired(soon)).isTrue();
    }

    @Test
    void developerAppsComeFirstThenDevelopmentKeys() {
        ConfiguredCredentialResolver dev = new ConfiguredCredentialResolver(new DevCredentialsProperties(
                List.of(new DevApiKey("acme", "dev-key", "demo", "dev@example.com", List.of("demo")))));
        CredentialResolver resolver = new CompositeCredentialResolver(List.of(new ManagedCredentialResolver(registry), dev));

        assertThat(resolver.resolve("acme", "dev", "key-1").map(AppCredential::appName)).hasValue("mobile");
        AppCredential devApp = resolver.resolve("acme", "dev", "dev-key").orElseThrow();
        assertThat(devApp.productFor("anything", "/x").map(ApiProductGrant::name)).hasValue("demo");
        assertThat(resolver.resolve("globex", "dev", "dev-key")).isEmpty();
    }

    @Test
    void removedEnvironmentsHaveNoCredentials() {
        registry.remove("acme/dev");
        assertThat(registry.serves("acme", "dev")).isFalse();
        assertThat(registry.index("acme", "dev").findByKey("key-1")).isEmpty();
    }
}
