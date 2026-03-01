package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.Permission;
import com.fraud.backend.data.entity.accessmanagement.RolePermission;
import com.fraud.backend.data.entity.accessmanagement.RolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
    
    // Récupérer les permissions par ID de rôle
    @Query("SELECT rp.permission FROM RolePermission rp WHERE rp.id.roleId = :roleId")
    List<Permission> findPermissionsByRoleId(@Param("roleId") Long roleId);
    
    // Récupérer les IDs des permissions par ID de rôle
    @Query("SELECT rp.id.permissionId FROM RolePermission rp WHERE rp.id.roleId = :roleId")
    List<Long> findPermissionIdsByRoleId(@Param("roleId") Long roleId);
    
    // Vérifier si une permission existe pour un rôle
    boolean existsByIdRoleIdAndIdPermissionId(Long roleId, Long permissionId);
    
    // Supprimer toutes les permissions d'un rôle
    void deleteByIdRoleId(Long roleId);
}