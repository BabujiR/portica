package com.portica.management.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByOrganizationIdOrderByOccurredAtDesc(UUID organizationId, Pageable pageable);

    /**
     * Filtered search, newest first. Empty {@code q} and {@code resourceType} match everything;
     * {@code q} must be normalized with {@link com.portica.management.shared.SearchTerms}.
     */
    @Query("""
            select e from AuditEvent e
            where e.organizationId = :organizationId
              and (:resourceType = '' or e.resourceType = :resourceType)
              and e.occurredAt >= :from and e.occurredAt < :to
              and (:q = ''
                   or lower(e.action) like concat('%', :q, '%') escape '\\'
                   or lower(e.resourceName) like concat('%', :q, '%') escape '\\'
                   or lower(coalesce(e.actorEmail, e.actorSubject, '')) like concat('%', :q, '%') escape '\\'
                   or lower(coalesce(e.requestId, '')) like concat('%', :q, '%') escape '\\')
            order by e.occurredAt desc
            """)
    Page<AuditEvent> search(@Param("organizationId") UUID organizationId, @Param("resourceType") String resourceType,
                            @Param("from") Instant from, @Param("to") Instant to, @Param("q") String q,
                            Pageable pageable);

    @Query("select distinct e.resourceType from AuditEvent e where e.organizationId = :organizationId order by e.resourceType")
    List<String> resourceTypes(@Param("organizationId") UUID organizationId);
}
