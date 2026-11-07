package com.portica.management.sharedflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SharedFlowDeploymentRepository extends JpaRepository<SharedFlowDeployment, UUID> {

    Optional<SharedFlowDeployment> findByEnvironment_IdAndSharedFlow_Id(UUID environmentId, UUID sharedFlowId);

    boolean existsByEnvironment_Id(UUID environmentId);

    boolean existsBySharedFlow_Id(UUID sharedFlowId);

    boolean existsByRevision_Id(UUID revisionId);

    @Query("""
            select d from SharedFlowDeployment d
            join fetch d.sharedFlow join fetch d.revision
            where d.environment.id = :environmentId
            order by d.sharedFlow.name
            """)
    List<SharedFlowDeployment> findLive(@Param("environmentId") UUID environmentId);

    @Query("""
            select d from SharedFlowDeployment d
            join fetch d.environment join fetch d.revision
            where d.sharedFlow.id = :sharedFlowId
            order by d.environment.name
            """)
    List<SharedFlowDeployment> findBySharedFlow(@Param("sharedFlowId") UUID sharedFlowId);
}
