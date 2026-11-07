package com.portica.management.developer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeveloperAppRepository extends JpaRepository<DeveloperApp, UUID> {

    Optional<DeveloperApp> findByDeveloper_IdAndName(UUID developerId, String name);

    boolean existsByDeveloper_IdAndName(UUID developerId, String name);

    Page<DeveloperApp> findByDeveloper_Id(UUID developerId, Pageable pageable);

    /** Apps of the organization; {@code q} must be normalized by {@code SearchTerms}. */
    @Query(value = """
            select a from DeveloperApp a join fetch a.developer d
            where a.organization.id = :organizationId
              and (lower(a.name) like concat('%', :q, '%') escape '\\'
                   or lower(a.displayName) like concat('%', :q, '%') escape '\\'
                   or d.email like concat('%', :q, '%') escape '\\')
            """, countQuery = """
            select count(a) from DeveloperApp a join a.developer d
            where a.organization.id = :organizationId
              and (lower(a.name) like concat('%', :q, '%') escape '\\'
                   or lower(a.displayName) like concat('%', :q, '%') escape '\\'
                   or d.email like concat('%', :q, '%') escape '\\')
            """)
    Page<DeveloperApp> search(@Param("organizationId") UUID organizationId, @Param("q") String q, Pageable pageable);
}
