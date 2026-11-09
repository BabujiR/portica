package com.portica.management.mcp;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface McpServerRepository extends JpaRepository<McpServer, UUID> {

    List<McpServer> findByOrganization_IdOrderByName(UUID organizationId);

    Optional<McpServer> findByOrganization_IdAndName(UUID organizationId, String name);

    boolean existsByOrganization_IdAndName(UUID organizationId, String name);

    /** Row lock while refreshing tokens, so concurrent refreshes don't burn a rotated refresh token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from McpServer s where s.id = :id")
    Optional<McpServer> lockById(@Param("id") UUID id);
}
