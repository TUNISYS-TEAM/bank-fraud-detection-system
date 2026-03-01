package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.accessmanagement.RoleRepository;
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
    private final RoleRepository roleRepository;  // Ajouté
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

    public AppUser getByUuid (UUID uuid) {
        return appUserRepository.findByUuid(uuid).orElseThrow(() -> new EntityNotFoundException("Utilisateur non trouvé " + uuid));
    }

    public AppUser getByEmail(String email) {
        return appUserRepository.findByEmail(email).orElseThrow(() -> new EntityNotFoundException("Utilisateur non trouvé " + email));
    }

    // Modifié pour utiliser roleId
    public Page<AppUser> getByRoleId(Long roleId, Pageable pageable) {
        return appUserRepository.findByRoleId(roleId, pageable);
    }

    // Create - modifié pour utiliser roleId
    @Transactional
    public AppUser createUser(String email, String password, String firstName,
                               String lastName, String phoneNumber,
                               LocalDate dateOfBirth, String address, Long roleId) {

        if (appUserRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email déjà existant: " + email);
        }

        // Récupérer le rôle
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + roleId));

        // Création dans keycloak (utilise le nom du rôle)
        String keycloakId = keycloakService.createUser(email, firstName, lastName, password, role.getName());

        // Création dans la base de données
        AppUser user = AppUser.builder()
                .uuid(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .phoneNumber(phoneNumber)
                .dateOfBirth(dateOfBirth)
                .address(address)
                .role(role)  // Maintenant c'est un objet Role
                .isActive(true)
                .build();

        AppUser savedUser = appUserRepository.save(user);

        // Sauvegarder le mapping entre l'utilisateur et son ID Keycloak
        KeycloakMapping mapping = KeycloakMapping.builder()
                .keycloakId(keycloakId)
                .user(savedUser)
                .username(email)
                .email(email)
                .lastSync(LocalDateTime.now())
                .build();

        keycloakMappingRepository.save(mapping);

        log.info("Utilisateur créé avec succès: {} avec l'ID: {} et rôle: {}", email, keycloakId, role.getName());
        return savedUser;
    }

    // Modification (inchangé)
    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName,
                               String phoneNumber, String address, LocalDate dateOfBirth) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

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

    // Change role - modifié pour utiliser roleId
    @Transactional
    public AppUser changeRole(Long id, Long newRoleId) {
        AppUser user = getById(id);
        
        Role newRole = roleRepository.findById(newRoleId)
                .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + newRoleId));

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        // Supprimer l'ancien rôle et assigner le nouveau rôle dans Keycloak
        keycloakService.removeRole(mapping.getKeycloakId(), user.getRole().getName());
        keycloakService.assignRole(mapping.getKeycloakId(), newRole.getName());

        user.setRole(newRole);
        return appUserRepository.save(user);
    }

    // Activer ou désactiver utilisateur (inchangé)
    @Transactional
    public void desactivateUser(Long id) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.disableUser(mapping.getKeycloakId());
        user.setIsActive(false);
        appUserRepository.save(user);
        log.info("Utilisateur désactivé: {}", user.getEmail());
    }

    @Transactional
    public void activateUser(Long id) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.enableUser(mapping.getKeycloakId());  
        user.setIsActive(true);
        appUserRepository.save(user);
        log.info("Utilisateur activé: {}", user.getEmail());
    }

    // Supprimer utilisateur (inchangé)
    @Transactional
    public void deleteUser(Long id) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.deleteUser(mapping.getKeycloakId());
        keycloakMappingRepository.delete(mapping);  
        appUserRepository.delete(user);             
        log.info("Utilisateur supprimé: {}", user.getEmail());
    }

    // Méthodes utilitaires pour obtenir roleId
    public Long getRoleIdByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getId();
    }

    public String getRoleNameByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getName();
    }
}