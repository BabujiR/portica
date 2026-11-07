package com.portica.management.organization;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> lockById(@Param("id") UUID id);

    boolean existsByName(String name);

    /** All organizations (platform admin view). {@code q} must be normalized by {@code SearchTerms}. */
    @Query("""
            select o from Organization o
            where lower(o.name) like concat('%', :q, '%') escape '\\'
               or lower(o.displayName) like concat('%', :q, '%') escape '\\'
            """)
    Page<Organization> search(@Param("q") String q, Pageable pageable);

    /** Active organizations the user has at least one role binding in. */
    @Query(value = """
            select o from Organization o
            where o.status = :status
              and exists (select rb.id from RoleBinding rb where rb.organization = o and rb.user.id = :userId)
              and (lower(o.name) like concat('%', :q, '%') escape '\\'
                   or lower(o.displayName) like concat('%', :q, '%') escape '\\')
            """,
            countQuery = """
            select count(o) from Organization o
            where o.status = :status
              and exists (select rb.id from RoleBinding rb where rb.organization = o and rb.user.id = :userId)
              and (lower(o.name) like concat('%', :q, '%') escape '\\'
                   or lower(o.displayName) like concat('%', :q, '%') escape '\\')
            """)
    Page<Organization> searchForMember(@Param("userId") UUID userId,
                                       @Param("status") OrganizationStatus status,
                                       @Param("q") String q,
                                       Pageable pageable);
}
