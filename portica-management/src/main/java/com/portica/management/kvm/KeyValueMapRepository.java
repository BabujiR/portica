package com.portica.management.kvm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KeyValueMapRepository extends JpaRepository<KeyValueMap, UUID> {

    @Query("select m from KeyValueMap m where m.organization.id = :organizationId and m.environment is null order by m.name")
    List<KeyValueMap> findOrganizationMaps(@Param("organizationId") UUID organizationId);

    @Query("select m from KeyValueMap m where m.organization.id = :organizationId and m.environment is null and m.name = :name")
    Optional<KeyValueMap> findOrganizationMap(@Param("organizationId") UUID organizationId, @Param("name") String name);

    List<KeyValueMap> findByEnvironment_IdOrderByName(UUID environmentId);

    Optional<KeyValueMap> findByEnvironment_IdAndName(UUID environmentId, String name);
}
