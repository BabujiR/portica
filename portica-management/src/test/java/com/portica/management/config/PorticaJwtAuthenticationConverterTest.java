package com.portica.management.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PorticaJwtAuthenticationConverterTest {

    private final PorticaJwtAuthenticationConverter converter = new PorticaJwtAuthenticationConverter();

    @Test
    void mapsScopesAndRealmRoles() {
        Jwt jwt = jwt(Map.of(
                "scope", "openid profile",
                "realm_access", Map.of("roles", List.of("portica-platform-admin", "offline_access"))));

        var auth = converter.convert(jwt);

        assertThat(auth.getName()).isEqualTo("subject-1");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile",
                        "REALM_portica-platform-admin", "REALM_offline_access");
    }

    @Test
    void toleratesMissingOrMalformedRealmAccess() {
        assertThat(converter.convert(jwt(Map.of("scope", "openid"))).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("SCOPE_openid");

        assertThat(converter.convert(jwt(Map.of("realm_access", "not-a-map"))).getAuthorities()).isEmpty();
        assertThat(converter.convert(jwt(Map.of("realm_access", Map.of("roles", "not-a-list")))).getAuthorities())
                .isEmpty();
    }

    @Test
    void platformAdminAuthorityUsesConfiguredRole() {
        var props = new PorticaSecurityProperties("custom-admin", "custom-runtime", null, null);
        assertThat(props.platformAdminAuthority()).isEqualTo("REALM_custom-admin");
        assertThat(props.runtimeAuthority()).isEqualTo("REALM_custom-runtime");

        var defaults = new PorticaSecurityProperties(null, null, null, null);
        assertThat(defaults.platformAdminAuthority()).isEqualTo("REALM_portica-platform-admin");
        assertThat(defaults.runtimeAuthority()).isEqualTo("REALM_portica-runtime");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("subject-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
