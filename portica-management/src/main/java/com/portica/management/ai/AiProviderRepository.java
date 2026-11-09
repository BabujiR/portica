package com.portica.management.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiProviderRepository extends JpaRepository<AiProvider, UUID> {

    List<AiProvider> findByOrganization_IdOrderByName(UUID organizationId);

    Optional<AiProvider> findByOrganization_IdAndName(UUID organizationId, String name);
}
