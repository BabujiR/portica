package com.portica.management.environment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.portica.management.organization.OrganizationStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {

    Optional<Environment> findByOrganization_IdAndName(UUID organizationId, String name);

    boolean existsByOrganization_IdAndName(UUID organizationId, String name);

    List<Environment> findByOrganization_Id(UUID organizationId);

    /** Rows of [organization name, environment name, config version] for the gateway runtime index. */
    @Query("""
            select o.name, e.name, e.configVersion
            from Environment e join e.organization o
            where o.status = :status
            order by o.name, e.name
            """)
    List<Object[]> findRuntimeVersions(@Param("status") OrganizationStatus status);

    /** {@code q} must be normalized by {@code SearchTerms}. */
    @Query("""
            select e from Environment e
            where e.organization.id = :organizationId
              and (lower(e.name) like concat('%', :q, '%') escape '\\'
                   or lower(e.displayName) like concat('%', :q, '%') escape '\\')
            """)
    Page<Environment> search(@Param("organizationId") UUID organizationId, @Param("q") String q, Pageable pageable);
}
