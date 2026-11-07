package com.portica.management.deployment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    Optional<Deployment> findByEnvironment_IdAndApiProxy_Id(UUID environmentId, UUID apiProxyId);

    Optional<Deployment> findByEnvironment_IdAndBasePath(UUID environmentId, String basePath);

    boolean existsByEnvironment_Id(UUID environmentId);

    boolean existsByApiProxy_Id(UUID apiProxyId);

    boolean existsByRevision_Id(UUID revisionId);

    /** Everything live in an environment, with proxy and revision loaded (runtime snapshot, listings). */
    @Query("""
            select d from Deployment d
            join fetch d.apiProxy join fetch d.revision
            where d.environment.id = :environmentId
            order by d.basePath
            """)
    List<Deployment> findLive(@Param("environmentId") UUID environmentId);

    /** Where a proxy is deployed, with environment and revision loaded. */
    @Query("""
            select d from Deployment d
            join fetch d.environment join fetch d.revision
            where d.apiProxy.id = :apiProxyId
            order by d.environment.name
            """)
    List<Deployment> findByProxy(@Param("apiProxyId") UUID apiProxyId);

    /** Whether any live revision in the environment routes to the named target server. */
    @Query("""
            select count(d) > 0 from Deployment d
            where d.environment.id = :environmentId and d.revision.targetServer = :targetServer
            """)
    boolean isTargetServerInUse(@Param("environmentId") UUID environmentId,
                                @Param("targetServer") String targetServer);
}
