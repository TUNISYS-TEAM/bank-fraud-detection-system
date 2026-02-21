package com.fraud.backend.data.entity.accessmanagement;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "role_permission")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {
    
    @EmbeddedId
    private RolePermissionId id;
    
    @ManyToOne
    @MapsId("roleId")
    @JoinColumn(name = "role_id")
    private Role role;
    
    @ManyToOne
    @MapsId("permissionId")
    @JoinColumn(name = "permission_id")
    private Permission permission;
}

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
class RolePermissionId implements java.io.Serializable {
    private Long roleId;
    private Long permissionId;
}