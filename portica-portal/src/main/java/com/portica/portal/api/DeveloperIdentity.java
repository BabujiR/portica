package com.portica.portal.api;

import com.portica.portal.config.PortalProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/** The signed-in developer: the verified e-mail address in their token is their identity in every organization. */
@Component
public class DeveloperIdentity {

    private final boolean requireVerified;

    public DeveloperIdentity(PortalProperties properties) {
        this.requireVerified = properties.requireVerifiedEmail();
    }

    /** E-mail of the authenticated developer; 403 when the token has none or it isn't verified. */
    public String email(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to continue");
        }
        Jwt jwt = token.getToken();
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account has no e-mail address");
        }
        if (requireVerified && !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your e-mail address first");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
