package com.portica.management.rbac;

import com.portica.management.organization.OrganizationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RoleBindingRepository extends JpaRepository<RoleBinding, UUID> {

    /** Effective permissions of a user in an organization (empty when not a member or org inactive). */
    @Query("""
            select distinct p
            from RoleBinding rb join rb.role r join r.permissions p
            where rb.user.id = :userId
              and rb.organization.name = :organizationName
              and rb.organization.status = :status
            """)
    List<Permission> findPermissions(@Param("userId") UUID userId,
                                     @Param("organizationName") String organizationName,
                                     @Param("status") OrganizationStatus status);

    @Query("""
            select rb from RoleBinding rb join fetch rb.role
            where rb.organization.id = :organizationId and rb.user.id = :userId
            """)
    List<RoleBinding> findForMember(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId);

    @Query("""
            select rb from RoleBinding rb join fetch rb.role
            where rb.organization.id = :organizationId and rb.user.id in :userIds
            """)
    List<RoleBinding> findForMembers(@Param("organizationId") UUID organizationId,
                                     @Param("userIds") Collection<UUID> userIds);

    @Query("""
            select rb from RoleBinding rb join fetch rb.organization join fetch rb.role
            where rb.user.id = :userId and rb.organization.status = :status
            order by rb.organization.name
            """)
    List<RoleBinding> findForUser(@Param("userId") UUID userId, @Param("status") OrganizationStatus status);

    boolean existsByOrganization_IdAndUser_Id(UUID organizationId, UUID userId);

    long countByOrganization_IdAndRole_Code(UUID organizationId, String roleCode);
}
