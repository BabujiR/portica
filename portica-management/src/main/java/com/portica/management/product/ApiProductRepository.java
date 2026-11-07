package com.portica.management.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiProductRepository extends JpaRepository<ApiProduct, UUID> {

    Optional<ApiProduct> findByOrganization_IdAndName(UUID organizationId, String name);

    boolean existsByOrganization_IdAndName(UUID organizationId, String name);

    List<ApiProduct> findByOrganization_Id(UUID organizationId);

    List<ApiProduct> findByOrganization_IdAndNameIn(UUID organizationId, Collection<String> names);

    /** {@code q} must be normalized by {@code SearchTerms}. */
    @Query("""
            select p from ApiProduct p
            where p.organization.id = :organizationId
              and (lower(p.name) like concat('%', :q, '%') escape '\\'
                   or lower(p.displayName) like concat('%', :q, '%') escape '\\')
            """)
    Page<ApiProduct> search(@Param("organizationId") UUID organizationId, @Param("q") String q, Pageable pageable);
}
