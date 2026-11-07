package com.portica.management.identity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByKeycloakSubject(String keycloakSubject);

    /** Lightweight sign-in timestamp update; intentionally bypasses versioning and auditing. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW) // never joins a read-only caller transaction
    @Query("update User u set u.lastLoginAt = :seenAt where u.id = :id")
    int touchLastLogin(@Param("id") UUID id, @Param("seenAt") Instant seenAt);

    /** Email must already be normalized (lower case). */
    Optional<User> findByEmail(String email);

    @Query(value = """
            select u from User u
            where exists (select rb.id from RoleBinding rb
                          where rb.user = u and rb.organization.id = :organizationId)
              and (lower(coalesce(u.email, '')) like concat('%', :q, '%') escape '\\'
                   or lower(u.displayName) like concat('%', :q, '%') escape '\\')
            """,
            countQuery = """
            select count(u) from User u
            where exists (select rb.id from RoleBinding rb
                          where rb.user = u and rb.organization.id = :organizationId)
              and (lower(coalesce(u.email, '')) like concat('%', :q, '%') escape '\\'
                   or lower(u.displayName) like concat('%', :q, '%') escape '\\')
            """)
    Page<User> searchMembers(@Param("organizationId") UUID organizationId, @Param("q") String q, Pageable pageable);
}
