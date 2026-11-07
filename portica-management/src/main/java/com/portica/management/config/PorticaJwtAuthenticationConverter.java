package com.portica.management.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converts a Keycloak access token into an authentication.
 * <p>
 * Authorities = OAuth2 scopes ({@code SCOPE_*}) plus Keycloak realm roles ({@code REALM_*}).
 * Only realm roles are used for coarse platform-level decisions (platform admin); all
 * organization-scoped authorization is evaluated by Portica from its own role bindings.
 */
public class PorticaJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopes.convert(jwt));
        authorities.addAll(realmRoles(jwt));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    static Set<GrantedAuthority> realmRoles(Jwt jwt) {
        Set<GrantedAuthority> result = new LinkedHashSet<>();
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && !name.isBlank()) {
                    result.add(new SimpleGrantedAuthority(PorticaSecurityProperties.REALM_ROLE_PREFIX + name));
                }
            }
        }
        return result;
    }
}
