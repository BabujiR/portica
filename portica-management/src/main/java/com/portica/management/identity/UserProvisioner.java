package com.portica.management.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Just-in-time provisioning of the local user record on first sign-in.
 * <p>
 * Runs in its own transaction so a concurrent first request that loses the race on the
 * unique subject constraint fails in isolation and can simply re-read the winner's row.
 * <p>
 * Security rule: an invited record is only linked when the token's email is <b>verified</b>;
 * otherwise anyone could claim an invitation by registering the same address unverified.
 */
@Component
public class UserProvisioner {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioner.class);

    private final UserRepository users;

    public UserProvisioner(UserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User provision(String subject, String email, boolean emailVerified, String displayName) {
        Optional<User> existing = users.findByKeycloakSubject(subject);
        if (existing.isPresent()) {
            return existing.get();
        }
        String normalizedEmail = User.normalizeEmail(email);
        Optional<User> sameEmail = normalizedEmail == null ? Optional.empty() : users.findByEmail(normalizedEmail);

        if (sameEmail.isPresent()) {
            User candidate = sameEmail.get();
            if (candidate.getStatus() == UserStatus.INVITED && emailVerified) {
                candidate.activate(subject, displayName);
                log.info("Linked invited user {} to identity {}", candidate.getId(), subject);
                return candidate;
            }
            // Email is taken (or unverified for an invite): create the account without claiming the email.
            log.warn("Identity {} presented email already associated with user {}; provisioning without email",
                    subject, candidate.getId());
            return users.save(User.active(subject, null, displayName));
        }
        User created = users.save(User.active(subject, normalizedEmail, displayName));
        log.info("Provisioned user {} for identity {}", created.getId(), subject);
        return created;
    }
}
