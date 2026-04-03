package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.user.UserMapper;
import com.fraud.backend.api.dto.accessmanagement.user.request.ChangePasswordRequest;
import com.fraud.backend.api.dto.accessmanagement.user.request.UpdateProfileRequest;
import com.fraud.backend.api.dto.accessmanagement.user.response.UserResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.service.accessmanagement.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ProfileController {

    private final UserService userService;
    private final UserMapper userMapper;

    /**
     * GET /api/profile — Get current user's full profile
     */
    @GetMapping
    public ResponseEntity<UserResponse> getProfile(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        AppUser user = userService.getByEmail(email);

        UserResponse response = userMapper.toResponse(user);
        // Enrich with role name and manager info
        if (user.getRole() != null) {
            response.setRoleName(user.getRole().getName());
        }
        if (user.getManager() != null) {
            response.setManagerId(user.getManager().getId());
            response.setManagerName(user.getManager().getFirstName() + " " + user.getManager().getLastName());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * PUT /api/profile — Update current user's personal information
     */
    @PutMapping
    public ResponseEntity<UserResponse> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {

        String email = jwt.getClaimAsString("email");
        log.info("Profile update requested by: {}", email);

        AppUser updatedUser = userService.updateProfile(
                email,
                request.getFirstName(),
                request.getLastName(),
                request.getPhoneNumber(),
                request.getAddress(),
                request.getDateOfBirth()
        );

        UserResponse response = userMapper.toResponse(updatedUser);
        if (updatedUser.getRole() != null) {
            response.setRoleName(updatedUser.getRole().getName());
        }
        if (updatedUser.getManager() != null) {
            response.setManagerId(updatedUser.getManager().getId());
            response.setManagerName(updatedUser.getManager().getFirstName() + " " + updatedUser.getManager().getLastName());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * PUT /api/profile/password — Change current user's password
     */
    @PutMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {

        String email = jwt.getClaimAsString("email");
        log.info("Password change requested by: {}", email);

        userService.changePassword(email, request.getCurrentPassword(), request.getNewPassword());

        return ResponseEntity.ok(Map.of("message", "Mot de passe modifié avec succès"));
    }

    /**
     * GET /api/profile/check-phone — Check if a phone number is already taken
     */
    @GetMapping("/check-phone")
    public ResponseEntity<Boolean> checkPhoneTaken(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String phone) {

        String email = jwt.getClaimAsString("email");
        AppUser user = userService.getByEmail(email);

        boolean isTaken = userService.isPhoneTaken(phone, user.getUuid());
        return ResponseEntity.ok(isTaken);
    }
}