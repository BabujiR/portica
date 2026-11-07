package com.portica.management.identity;

import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Local profile of a Portica console/API user. Authentication is delegated to Keycloak;
 * the record is linked to the identity by the token subject ({@code sub}).
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(name = "keycloak_subject", unique = true)
    private String keycloakSubject;

    @Column(name = "email", unique = true, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
        // JPA
    }

    private User(String keycloakSubject, String email, String displayName, UserStatus status) {
        this.keycloakSubject = keycloakSubject;
        this.email = normalizeEmail(email);
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.status = Objects.requireNonNull(status, "status");
    }

    /** A user who signed in for the first time. Email may be null (e.g. already used by another account). */
    public static User active(String keycloakSubject, String email, String displayName) {
        User user = new User(Objects.requireNonNull(keycloakSubject, "keycloakSubject"), email, displayName,
                UserStatus.ACTIVE);
        user.lastLoginAt = Instant.now();
        return user;
    }

    /** A user added to an organization by email before their first sign-in. */
    public static User invited(String email) {
        String normalized = normalizeEmail(Objects.requireNonNull(email, "email"));
        return new User(null, normalized, normalized, UserStatus.INVITED);
    }

    /** Links an invited record to the Keycloak identity that just signed in with the same verified email. */
    public void activate(String keycloakSubject, String displayName) {
        if (status != UserStatus.INVITED) {
            throw new IllegalStateException("Only invited users can be activated");
        }
        this.keycloakSubject = Objects.requireNonNull(keycloakSubject, "keycloakSubject");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.status = UserStatus.ACTIVE;
        this.lastLoginAt = Instant.now();
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    public String getKeycloakSubject() {
        return keycloakSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
