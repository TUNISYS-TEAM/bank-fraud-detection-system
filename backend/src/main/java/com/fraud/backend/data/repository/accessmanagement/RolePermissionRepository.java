package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.Permission;
import com.fraud.backend.data.entity.accessmanagement.RolePermission;
import com.fraud.backend.data.entity.accessmanagement.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {

    @Query("SELECT rp.permission FROM RolePermission rp WHERE rp.id.roleId = :roleId")
    List<Permission> findPermissionsByRoleId(@Param("roleId") Long roleId);

    @Query("SELECT rp.id.permissionId FROM RolePermission rp WHERE rp.id.roleId = :roleId")
    List<Long> findPermissionIdsByRoleId(@Param("roleId") Long roleId);

    boolean existsByIdRoleIdAndIdPermissionId(Long roleId, Long permissionId);

    void deleteByIdRoleId(Long roleId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM RolePermission rp WHERE rp.role.id = :roleId")
    int deleteByRoleId(@Param("roleId") Long roleId);
}