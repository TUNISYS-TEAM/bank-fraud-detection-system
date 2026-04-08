package com.fraud.backend.api.controller.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.user.request.ChangeManagerRequest;
import com.fraud.backend.api.dto.accessmanagement.user.request.ChangeRoleRequest;
import com.fraud.backend.api.dto.accessmanagement.user.request.CreateUserRequest;
import com.fraud.backend.api.dto.accessmanagement.user.request.UpdateUserRequest;
import com.fraud.backend.api.dto.accessmanagement.user.response.UserResponse;
import com.fraud.backend.api.dto.accessmanagement.user.response.UserSummaryResponse;
import com.fraud.backend.api.dto.accessmanagement.user.UserMapper;
import com.fraud.backend.data.service.accessmanagement.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    // ==================== BASIC CRUD ====================

    @GetMapping
    public ResponseEntity<Page<UserSummaryResponse>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return ResponseEntity.ok(
                userService.getAllUsers(pageable).map(userMapper::toSummaryResponse));
    }

    @GetMapping("/count/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Long> getActiveCount() {
        return ResponseEntity.ok(userService.countActiveUsers());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userMapper.toResponse(userService.getById(id)));
    }

    @GetMapping("/uuid/{uuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> getByUuid(@PathVariable UUID uuid) {
        return ResponseEntity.ok(userMapper.toResponse(userService.getByUuid(uuid)));
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> getByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userMapper.toResponse(userService.getByEmail(email)));
    }

    // ==================== CREATE USER ====================
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                userMapper.toResponse(
                        userService.createUser(
                                request.getEmail(),
                                request.getPassword(),
                                request.getFirstName(),
                                request.getLastName(),
                                request.getPhoneNumber(),
                                request.getDateOfBirth(),
                                request.getAddress(),
                                request.getRoleId(),
                                request.getIsActive(),
                                request.getManagerId())));
    }

    // ==================== UPDATE ====================
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(
                userMapper.toResponse(
                        userService.updateUser(
                                id,
                                request.getFirstName(),
                                request.getLastName(),
                                request.getEmail(),
                                request.getPhoneNumber(),
                                request.getAddress(),
                                request.getDateOfBirth(),
                                request.getRoleId(),
                                request.getIsActive(),
                                request.getManagerId())));
    }

    // ==================== ROLE & MANAGER ====================
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> changeRole(@PathVariable Long id,
            @Valid @RequestBody ChangeRoleRequest request) {
        return ResponseEntity.ok(userMapper.toResponse(
                userService.changeRole(id, request.getRoleId())));
    }

    @PatchMapping("/{id}/manager")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> changeManager(@PathVariable Long id,
            @Valid @RequestBody ChangeManagerRequest request) {
        return ResponseEntity.ok(userMapper.toResponse(
                userService.changeManager(id, request.getManagerId())));
    }

    // ==================== EMAIL VERIFICATION (NEW) ====================

    /**
     * Verify email using the code sent by Keycloak
     * Call this when user enters the verification code/link
     */
    @PostMapping("/{id}/verify-email")
    @PreAuthorize("isAuthenticated()")   // or hasRole('ADMIN') if you prefer
    public ResponseEntity<Void> verifyEmail(@PathVariable Long id) {
        userService.verifyEmail(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Resend verification email
     */
    @PostMapping("/{id}/resend-verification")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> resendVerificationEmail(@PathVariable Long id) {
        userService.resendVerificationEmail(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> getCurrentUser() {
        return ResponseEntity.ok(userMapper.toResponse(userService.getCurrentUser()));
    }

    // ==================== OTHER OPERATIONS ====================

    @GetMapping("/available-managers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UserSummaryResponse>> getAvailableManagers() {
        return ResponseEntity.ok(
                userService.getAvailableManagers().stream()
                        .map(userMapper::toSummaryResponse)
                        .toList());
    }

    @GetMapping("/{id}/subordinates")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserSummaryResponse>> getSubordinates(@PathVariable Long id) {
        return ResponseEntity.ok(
                userService.getSubordinates(id).stream()
                        .map(userMapper::toSummaryResponse)
                        .toList());
    }

    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> activate(@PathVariable Long id) {
        userService.activateUser(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        userService.desactivateUser(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/by-role/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<UserSummaryResponse>> getByRoleId(
            @PathVariable Long roleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return ResponseEntity.ok(
                userService.getUsersByRoleId(roleId, pageable).map(userMapper::toSummaryResponse));
    }
}