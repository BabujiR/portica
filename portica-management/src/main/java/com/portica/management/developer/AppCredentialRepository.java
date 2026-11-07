package com.portica.management.developer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppCredentialRepository extends JpaRepository<AppCredential, UUID> {

    List<AppCredential> findByApp_IdOrderByCreatedAtAsc(UUID appId);

    Optional<AppCredential> findByIdAndApp_Id(UUID id, UUID appId);

    /**
     * Credentials the gateway may honour: approved credential, approved app, active developer.
     * Expiry and product grants are filtered by the caller.
     */
    @Query("""
            select distinct c from AppCredential c
              join fetch c.app a
              join fetch a.developer d
              left join fetch c.products g
              left join fetch g.apiProduct
            where a.organization.id = :organizationId
              and c.status = com.portica.management.developer.CredentialStatus.APPROVED
              and a.status = com.portica.management.developer.AppStatus.APPROVED
              and d.status = com.portica.management.developer.DeveloperStatus.ACTIVE
            """)
    List<AppCredential> findLive(@Param("organizationId") UUID organizationId);
}
