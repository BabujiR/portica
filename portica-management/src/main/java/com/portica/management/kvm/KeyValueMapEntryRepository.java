package com.portica.management.kvm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KeyValueMapEntryRepository extends JpaRepository<KeyValueMapEntry, UUID> {

    Page<KeyValueMapEntry> findByMap_IdOrderByKey(UUID mapId, Pageable pageable);

    Optional<KeyValueMapEntry> findByMap_IdAndKey(UUID mapId, String key);

    List<KeyValueMapEntry> findByMap_IdIn(Collection<UUID> mapIds);

    long countByMap_Id(UUID mapId);
}
