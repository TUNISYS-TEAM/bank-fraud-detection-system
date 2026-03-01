package com.fraud.backend.data.entity.accessmanagement;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionId implements Serializable {

    private Long roleId;
    private Long permissionId;
}