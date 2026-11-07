package com.portica.management.proxy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ApiProxyRepository extends JpaRepository<ApiProxy, UUID> {

    Optional<ApiProxy> findByOrganization_IdAndName(UUID organizationId, String name);

    boolean existsByOrganization_IdAndName(UUID organizationId, String name);

    /** {@code q} must be normalized by {@code SearchTerms}. */
    @Query("""
            select p from ApiProxy p
            where p.organization.id = :organizationId
              and (lower(p.name) like concat('%', :q, '%') escape '\\'
                   or lower(p.displayName) like concat('%', :q, '%') escape '\\')
            """)
    Page<ApiProxy> search(@Param("organizationId") UUID organizationId, @Param("q") String q, Pageable pageable);
}
