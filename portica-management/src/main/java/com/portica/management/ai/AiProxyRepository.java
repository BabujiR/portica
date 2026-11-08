package com.portica.management.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiProxyRepository extends JpaRepository<AiProxy, UUID> {

    List<AiProxy> findByOrganization_IdOrderByName(UUID organizationId);

    Optional<AiProxy> findByOrganization_IdAndName(UUID organizationId, String name);

    boolean existsByOrganization_IdAndName(UUID organizationId, String name);
}
