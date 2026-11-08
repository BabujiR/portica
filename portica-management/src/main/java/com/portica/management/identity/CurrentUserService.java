package com.portica.management.identity;

import com.portica.management.config.PorticaSecurityProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;
import java.time.Instant;

/**
 * Resolves the authenticated caller to a Portica {@link User}, provisioning it on first sign-in.
 * The result is cached for the duration of the HTTP request.
 */
@Service
public class CurrentUserService {

    private static final String CACHE_ATTRIBUTE = CurrentUserService.class.getName() + ".currentUser";

    /** Sign-in time is refreshed at most this often to avoid a write on every request. */
    private static final Duration LAST_LOGIN_RESOLUTION = Duration.ofMinutes(15);

    private final UserRepository users;
    private final UserProvisioner provisioner;
    private final PorticaSecurityProperties securityProperties;

    public CurrentUserService(UserRepository users, UserProvisioner provisioner,
                              PorticaSecurityProperties securityProperties) {
        this.users = users;
        this.provisioner = provisioner;
        this.securityProperties = securityProperties;
    }

    public CurrentUser currentUser() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null
                && attributes.getAttribute(CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof CurrentUser cached) {
            return cached;
        }
        Jwt jwt = currentJwt();
        User user = users.findByKeycloakSubject(jwt.getSubject()).orElseGet(() -> provision(jwt));
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new AccessDeniedException("User account is disabled");
        }
        Instant now = Instant.now();
        if (user.getLastLoginAt() == null || user.getLastLoginAt().isBefore(now.minus(LAST_LOGIN_RESOLUTION))) {
            users.touchLastLogin(user.getId(), now);
        }
        CurrentUser current = new CurrentUser(user.getId(), jwt.getSubject(), user.getEmail(),
                user.getDisplayName(), isPlatformAdmin());
        if (attributes != null) {
            attributes.setAttribute(CACHE_ATTRIBUTE, current, RequestAttributes.SCOPE_REQUEST);
        }
        return current;
    }

    public boolean isPlatformAdmin() {
        return hasAuthority(securityProperties.platformAdminAuthority());
    }

    /** True for gateway service accounts that read runtime configuration. */
    public boolean isPortalClient() {
        return hasAuthority(securityProperties.portalAuthority());
    }

    public boolean isRuntimeClient() {
        return hasAuthority(securityProperties.runtimeAuthority());
    }

    private static boolean hasAuthority(String required) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> required.equals(a.getAuthority()));
    }

    private User provision(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        boolean emailVerified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
        try {
            return provisioner.provision(jwt.getSubject(), email, emailVerified, displayName(jwt));
        } catch (DataIntegrityViolationException raceLost) {
            // Another request for the same identity provisioned it first.
            return users.findByKeycloakSubject(jwt.getSubject()).orElseThrow(() -> raceLost);
        }
    }

    static String displayName(Jwt jwt) {
        for (String claim : new String[] {"name", "preferred_username", "email"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }

    private static Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        throw new AuthenticationCredentialsNotFoundException("No bearer token authentication present");
    }
}
