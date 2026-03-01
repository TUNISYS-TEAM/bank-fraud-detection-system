package com.fraud.backend.api.controller;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    // GET /api/users
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<UserSummaryResponse>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return ResponseEntity.ok(
                userService.getAllUsers(pageable).map(userMapper::toSummaryResponse));
    }

    // GET /api/users/{id}
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userMapper.toResponse(userService.getById(id)));
    }

    // GET /api/users/uuid/{uuid}
    @GetMapping("/uuid/{uuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> getByUuid(@PathVariable UUID uuid) {
        return ResponseEntity.ok(userMapper.toResponse(userService.getByUuid(uuid)));
    }

    // GET /api/users/email/{email}
    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> getByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userMapper.toResponse(userService.getByEmail(email)));
    }

    // POST /api/users
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
                                request.getRoleId())));  
    }

    // PUT /api/users/{id}
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(
                userMapper.toResponse(
                        userService.updateUser(
                                id,
                                request.getFirstName(),
                                request.getLastName(),
                                request.getPhoneNumber(),
                                request.getAddress(),
                                request.getDateOfBirth())));
    }

    // PATCH /api/users/{id}/role
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> changeRole(@PathVariable Long id,
            @Valid @RequestBody ChangeRoleRequest request) {
        return ResponseEntity.ok(userMapper.toResponse(
                userService.changeRole(id, request.getRoleId())));  
    }

    // PATCH /api/users/{id}/activate
    @PatchMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> activate(@PathVariable Long id) {
        userService.activateUser(id);
        return ResponseEntity.noContent().build();
    }

    // PATCH /api/users/{id}/deactivate
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        userService.desactivateUser(id);
        return ResponseEntity.noContent().build();
    }

    // DELETE /api/users/{id}
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}