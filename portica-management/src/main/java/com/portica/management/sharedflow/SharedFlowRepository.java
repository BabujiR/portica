package com.portica.management.sharedflow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SharedFlowRepository extends JpaRepository<SharedFlow, UUID> {

    Optional<SharedFlow> findByOrganization_IdAndName(UUID organizationId, String name);

    boolean existsByOrganization_IdAndName(UUID organizationId, String name);

    /** {@code q} must be normalized by {@code SearchTerms}. */
    @Query("""
            select f from SharedFlow f
            where f.organization.id = :organizationId
              and (lower(f.name) like concat('%', :q, '%') escape '\\'
                   or lower(f.displayName) like concat('%', :q, '%') escape '\\')
            """)
    Page<SharedFlow> search(@Param("organizationId") UUID organizationId, @Param("q") String q, Pageable pageable);
}
