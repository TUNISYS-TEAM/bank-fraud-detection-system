package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
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

    public Page<AppUser> getByRole(String role, Pageable pageable) {
        return appUserRepository.findByRole(role, pageable);
    }

    // Create
    @Transactional
    public AppUser createUser(String email, String password, String firstName,
                               String lastName, String phoneNumber,
                               LocalDate dateOfBirth, String address, String role) {

        if (appUserRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email déjà existant: " + email);
        }

        // Création dans keycloak
        String keycloakId = keycloakService.createUser(email, firstName, lastName, password, role);

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
                .role(role)
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

        log.info("Utilisateur créé avec succès: {} avec l'ID: {}", email, keycloakId);
        return savedUser;
    }

    // Modification
    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName,
                               String phoneNumber, String address, LocalDate dateOfBirth) {
        AppUser user = getById(id);

        // Synchronisation avec Keycloak
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id).orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.updateUser(mapping.getKeycloakId(), firstName, lastName, user.getEmail());

        // Mise à jour de la base de données
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhoneNumber(phoneNumber);
        user.setAddress(address);
        user.setDateOfBirth(dateOfBirth);

        // Synchronisation timestamp
        mapping.setLastSync(LocalDateTime.now());
        keycloakMappingRepository.save(mapping);

        return appUserRepository.save(user);
    }

    // Change role
    @Transactional
    public AppUser changeRole(Long id, String newRole) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id).orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        // Supprimer l'ancien rôle et assigner le nouveau rôle dans Keycloak
        keycloakService.removeRole(mapping.getKeycloakId(), user.getRole());
        keycloakService.assignRole(mapping.getKeycloakId(), newRole);

        user.setRole(newRole);
        return appUserRepository.save(user);
    }

    // Activer ou désactiver utilisateur
    @Transactional
    public void desactivateUser(Long id) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id).orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.disableUser(mapping.getKeycloakId());
        user.setIsActive(false);
        appUserRepository.save(user);
        log.info("Utilisateur désactivé: {}", user.getEmail());
    }

    @Transactional
    public void activateUser(Long id) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id).orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.enableUser(mapping.getKeycloakId());  
        user.setIsActive(true);
        appUserRepository.save(user);
        log.info("Utilisateur activé: {}", user.getEmail());
    }

    // Supprimer utilisateur
    @Transactional
    public void deleteUser(Long id) {
        AppUser user = getById(id);

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id).orElseThrow(() -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        keycloakService.deleteUser(mapping.getKeycloakId());
        keycloakMappingRepository.delete(mapping);  
        appUserRepository.delete(user);             
        log.info("Utilisateur supprimé: {}", user.getEmail());
    }
}