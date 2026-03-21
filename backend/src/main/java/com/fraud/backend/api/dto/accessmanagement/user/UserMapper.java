package com.fraud.backend.api.dto.accessmanagement.user;

import com.fraud.backend.api.dto.accessmanagement.user.response.UserResponse;
import com.fraud.backend.api.dto.accessmanagement.user.response.UserSummaryResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(AppUser user) {
        if (user == null) {
            return null;
        }
        
        return UserResponse.builder()
                .id(user.getId())
                .uuid(user.getUuid())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phoneNumber(user.getPhoneNumber())
                .address(user.getAddress())
                .dateOfBirth(user.getDateOfBirth())
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .roleName(user.getRole() != null ? user.getRole().getName() : null)
                .managerId(user.getManager() != null ? user.getManager().getId() : null)
                .managerName(user.getManager() != null ? 
                        user.getManager().getFirstName() + " " + user.getManager().getLastName() : null)
                .isActive(user.getIsActive())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    public UserSummaryResponse toSummaryResponse(AppUser user) {
        if (user == null) {
            return null;
        }
        
        return UserSummaryResponse.builder()
                .id(user.getId())
                .uuid(user.getUuid())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .roleName(user.getRole() != null ? user.getRole().getName() : null)
                .managerId(user.getManager() != null ? user.getManager().getId() : null)
                .managerName(user.getManager() != null ? 
                        user.getManager().getFirstName() + " " + user.getManager().getLastName() : null)
                .isActive(user.getIsActive())
                .lastLogin(user.getLastLogin())
                .build();
    }
}