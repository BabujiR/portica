package com.portica.management.targetserver;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TargetServerRepository extends JpaRepository<TargetServer, UUID> {

    Optional<TargetServer> findByEnvironment_IdAndName(UUID environmentId, String name);

    boolean existsByEnvironment_IdAndName(UUID environmentId, String name);

    List<TargetServer> findByEnvironment_Id(UUID environmentId);

    /** {@code q} must be normalized by {@code SearchTerms}. */
    @Query("""
            select t from TargetServer t
            where t.environment.id = :environmentId
              and (lower(t.name) like concat('%', :q, '%') escape '\\'
                   or lower(t.host) like concat('%', :q, '%') escape '\\')
            """)
    Page<TargetServer> search(@Param("environmentId") UUID environmentId, @Param("q") String q, Pageable pageable);
}
