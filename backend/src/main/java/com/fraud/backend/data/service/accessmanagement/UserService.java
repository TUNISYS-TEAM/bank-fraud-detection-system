package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.api.dto.accessmanagement.user.response.UserResponse;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.accessmanagement.RoleRepository;
import com.fraud.backend.data.repository.accessmanagement.UserSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository appUserRepository;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final UserSessionRepository userSessionRepository;
    private final RoleRepository roleRepository;
    private final KeycloakService keycloakService;
    private final BCryptPasswordEncoder passwordEncoder;

    // ==================== READ METHODS ====================
    public Page<AppUser> getAllUsers(Pageable pageable) {
        return appUserRepository.findAll(pageable);
    }

    public AppUser getById(Long id) {
        return appUserRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur non trouvé " + id));
    }

    public AppUser getByUuid(UUID uuid) {
        return appUserRepository.findByUuid(uuid)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur non trouvé " + uuid));
    }

    public AppUser getByEmail(String email) {
        return appUserRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur non trouvé " + email));
    }

    public Page<AppUser> getByRoleId(Long roleId, Pageable pageable) {
        return appUserRepository.findByRoleId(roleId, pageable);
    }

    public long countActiveUsers() {
        return appUserRepository.countByIsActiveTrue();
    }
    
    // ==================== CREATE METHOD ====================
    @Transactional
    public AppUser createUser(String email, String password, String firstName,
            String lastName, String phoneNumber,
            LocalDate dateOfBirth, String address,
            Long roleId, Boolean isActive) {

        if (appUserRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email déjà existant: " + email);
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + roleId));

        String keycloakId = keycloakService.createUser(email, firstName, lastName, password, role.getName());

        boolean active = isActive != null ? isActive : true;

        AppUser user = AppUser.builder()
                .uuid(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .phoneNumber(phoneNumber)
                .dateOfBirth(dateOfBirth)
                .address(address)
                .role(role)
                .isActive(active)
                .build();

        AppUser savedUser = appUserRepository.save(user);

        if (!active) {
            try {
                keycloakService.disableUser(keycloakId);
            } catch (Exception e) {
                log.error("Erreur lors de la désactivation dans Keycloak: {}", e.getMessage());
            }
        }

        KeycloakMapping mapping = KeycloakMapping.builder()
                .keycloakId(keycloakId)
                .user(savedUser)
                .username(email)
                .email(email)
                .lastSync(LocalDateTime.now())
                .build();

        keycloakMappingRepository.save(mapping);

        return savedUser;
    }

    // ==================== UPDATE METHOD ====================
    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName, String email,
            String phoneNumber, String address, LocalDate dateOfBirth, Long roleId, Boolean isActive) {

        AppUser user = getById(id);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        boolean emailChanged = !user.getEmail().equals(email);
        boolean roleChanged = roleId != null && !user.getRole().getId().equals(roleId);
        boolean activeChanged = isActive != null && user.getIsActive() != isActive;

        // 1. Mise à jour Keycloak (infos de base)
        keycloakService.updateUser(mapping.getKeycloakId(), firstName, lastName, email);

        // 2. Gérer le changement de rôle si nécessaire
        if (roleChanged) {
            Role newRole = roleRepository.findById(roleId)
                    .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + roleId));

            keycloakService.removeRole(mapping.getKeycloakId(), user.getRole().getKeycloakRoleName());
            keycloakService.assignRole(mapping.getKeycloakId(), newRole.getKeycloakRoleName());

            user.setRole(newRole);
        }

        // 3. Gérer le changement d'activation si nécessaire
        if (activeChanged) {
            if (isActive) {
                keycloakService.enableUser(mapping.getKeycloakId());
            } else {
                keycloakService.disableUser(mapping.getKeycloakId());
            }
            user.setIsActive(isActive);
        }

        // 4. Mettre à jour les champs de l'utilisateur
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        user.setAddress(address);
        user.setDateOfBirth(dateOfBirth);

        // 5. Mettre à jour le mapping Keycloak
        mapping.setEmail(email);
        mapping.setUsername(email);
        mapping.setLastSync(LocalDateTime.now());
        keycloakMappingRepository.save(mapping);

        // 6. UN SEUL SAVE à la fin
        return appUserRepository.save(user);
    }

    // ==================== UTILITY METHODS ====================
    @Transactional
    public AppUser changeRole(Long id, Long newRoleId) {
        AppUser user = getById(id);
        Role newRole = roleRepository.findById(newRoleId)
                .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + newRoleId));

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.removeRole(mapping.getKeycloakId(), user.getRole().getKeycloakRoleName());
        keycloakService.assignRole(mapping.getKeycloakId(), newRole.getKeycloakRoleName());

        user.setRole(newRole);
        return appUserRepository.save(user);
    }

    @Transactional
    public void desactivateUser(Long id) {
        AppUser user = getById(id);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.disableUser(mapping.getKeycloakId());
        user.setIsActive(false);
        appUserRepository.save(user);
    }

    @Transactional
    public void activateUser(Long id) {
        AppUser user = getById(id);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.enableUser(mapping.getKeycloakId());
        user.setIsActive(true);
        appUserRepository.save(user);
    }

    // ==================== DELETE METHOD ====================
    @Transactional
    public void deleteUser(Long id) {
        AppUser user = getById(id);

        userSessionRepository.deleteByUserId(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        try {
            keycloakService.deleteUser(mapping.getKeycloakId());
        } catch (Exception e) {
            log.error("Erreur lors de la suppression dans Keycloak: {}", e.getMessage());
        }

        keycloakMappingRepository.delete(mapping);
        appUserRepository.delete(user);
    }

    // ==================== GETTERS ====================
    public Long getRoleIdByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getId();
    }

    public String getRoleNameByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getName();
    }

    public Page<AppUser> getUsersByRoleId(Long roleId, Pageable pageable) {
        return appUserRepository.findByRoleId(roleId, pageable);
    }
}