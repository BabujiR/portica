package com.portica.management.sharedflow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SharedFlowRevisionRepository extends JpaRepository<SharedFlowRevision, UUID> {

    Optional<SharedFlowRevision> findBySharedFlow_IdAndRevision(UUID sharedFlowId, int revision);

    Page<SharedFlowRevision> findBySharedFlow_IdOrderByRevisionDesc(UUID sharedFlowId, Pageable pageable);
}
