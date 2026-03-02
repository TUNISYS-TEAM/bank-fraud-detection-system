package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.accessmanagement.RoleRepository;
import com.fraud.backend.data.repository.accessmanagement.UserSessionRepository; // AJOUTÉ
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final UserSessionRepository userSessionRepository; // AJOUTÉ
    private final RoleRepository roleRepository;
    private final KeycloakService keycloakService;
    private final BCryptPasswordEncoder passwordEncoder;

    // Read
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
                log.info("Utilisateur désactivé dans Keycloak: {}", keycloakId);
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

        log.info("Utilisateur créé avec succès: {} avec l'ID: {}, rôle: {}, statut: {}",
                email, keycloakId, role.getName(), active ? "actif" : "inactif");
        return savedUser;
    }

    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName,
            String phoneNumber, String address, LocalDate dateOfBirth) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.updateUser(mapping.getKeycloakId(), firstName, lastName, user.getEmail());

        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhoneNumber(phoneNumber);
        user.setAddress(address);
        user.setDateOfBirth(dateOfBirth);

        mapping.setLastSync(LocalDateTime.now());
        keycloakMappingRepository.save(mapping);

        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser changeRole(Long id, Long newRoleId) {
        AppUser user = getById(id);

        Role newRole = roleRepository.findById(newRoleId)
                .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + newRoleId));

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.removeRole(mapping.getKeycloakId(), user.getRole().getName());
        keycloakService.assignRole(mapping.getKeycloakId(), newRole.getName());

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
        log.info("Utilisateur désactivé: {}", user.getEmail());
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
        log.info("Utilisateur activé: {}", user.getEmail());
    }

    /**
     * Supprimer un utilisateur en cascade (supprime d'abord les dépendances)
     */
    @Transactional
    public void deleteUser(Long id) {
        AppUser user = getById(id);

        // Supprimer d'abord les sessions de l'utilisateur
        userSessionRepository.deleteByUserId(id);
        log.debug("Sessions supprimées pour l'utilisateur: {}", user.getEmail());

        // Récupérer le mapping Keycloak
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        // Supprimer l'utilisateur dans Keycloak
        try {
            keycloakService.deleteUser(mapping.getKeycloakId());
            log.debug("Utilisateur supprimé dans Keycloak: {}", mapping.getKeycloakId());
        } catch (Exception e) {
            log.error("Erreur lors de la suppression dans Keycloak: {}", e.getMessage());
        }

        // Supprimer le mapping Keycloak
        keycloakMappingRepository.delete(mapping);
        log.debug("Mapping Keycloak supprimé pour l'utilisateur: {}", user.getEmail());

        // Enfin, supprimer l'utilisateur
        appUserRepository.delete(user);
        log.info("Utilisateur supprimé avec succès: {}", user.getEmail());
    }

    public Long getRoleIdByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getId();
    }

    public String getRoleNameByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getName();
    }
}