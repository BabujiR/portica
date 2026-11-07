package com.portica.management.proxy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiProxyRevisionRepository extends JpaRepository<ApiProxyRevision, UUID> {

    Optional<ApiProxyRevision> findByApiProxy_IdAndRevision(UUID apiProxyId, int revision);

    Page<ApiProxyRevision> findByApiProxy_IdOrderByRevisionDesc(UUID apiProxyId, Pageable pageable);
}
