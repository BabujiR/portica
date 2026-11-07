package com.portica.management.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/**
 * Fills {@code created_by} / {@code updated_by} from the authenticated caller.
 * Reads the token only (no database access) because it runs during flush.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {

    public static final String SYSTEM_ACTOR = "system";

    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.of(currentActor());
    }

    static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            String email = token.getToken().getClaimAsString("email");
            return email != null && !email.isBlank() ? email : token.getName();
        }
        return SYSTEM_ACTOR;
    }
}
