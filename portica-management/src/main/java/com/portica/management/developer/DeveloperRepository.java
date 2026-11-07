package com.portica.management.developer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeveloperRepository extends JpaRepository<Developer, UUID> {

    Optional<Developer> findByOrganization_IdAndEmail(UUID organizationId, String email);

    boolean existsByOrganization_IdAndEmail(UUID organizationId, String email);

    /** {@code q} must be normalized by {@code SearchTerms}. */
    @Query("""
            select d from Developer d
            where d.organization.id = :organizationId
              and (d.email like concat('%', :q, '%') escape '\\'
                   or lower(d.firstName) like concat('%', :q, '%') escape '\\'
                   or lower(d.lastName) like concat('%', :q, '%') escape '\\')
            """)
    Page<Developer> search(@Param("organizationId") UUID organizationId, @Param("q") String q, Pageable pageable);
}
