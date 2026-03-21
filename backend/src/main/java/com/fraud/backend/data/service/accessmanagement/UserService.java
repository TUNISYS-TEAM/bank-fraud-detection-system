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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

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

    // Hierarchical rules for manager assignment
    private static final Map<String, List<String>> ALLOWED_MANAGER_ROLES = new HashMap<>();
    
    static {
        // System roles hierarchy
        ALLOWED_MANAGER_ROLES.put("ANALYST", Arrays.asList("MANAGER", "ADMIN"));
        ALLOWED_MANAGER_ROLES.put("MANAGER", Arrays.asList("ADMIN"));
        ALLOWED_MANAGER_ROLES.put("ADMIN", Collections.emptyList()); // ADMIN cannot have a manager
        
        // Custom roles can be added dynamically via the setAllowedManagerRolesForCustomRole method
    }
    
    /**
     * Set allowed manager roles for a custom role
     * This allows dynamic configuration for custom roles
     */
    public void setAllowedManagerRolesForCustomRole(String customRoleName, List<String> allowedManagerRoles) {
        ALLOWED_MANAGER_ROLES.put(customRoleName, allowedManagerRoles);
        log.info("Allowed manager roles for role '{}' set to: {}", customRoleName, allowedManagerRoles);
    }
    
    /**
     * Validate if a manager can be assigned to a user based on role hierarchy
     */
    private void validateManagerAssignment(AppUser user, AppUser manager) {
        if (manager == null) {
            return; // No manager to validate
        }
        
        String userRole = user.getRole().getName();
        String managerRole = manager.getRole().getName();
        
        // Get allowed manager roles for this user role
        List<String> allowedRoles = ALLOWED_MANAGER_ROLES.getOrDefault(userRole, Collections.emptyList());
        
        // Check if the user role can have a manager
        if (allowedRoles.isEmpty() && manager != null) {
            throw new IllegalArgumentException(
                String.format("L'utilisateur avec le rôle '%s' ne peut pas avoir de manager", userRole)
            );
        }
        
        // Check if the manager's role is allowed
        if (!allowedRoles.contains(managerRole)) {
            throw new IllegalArgumentException(
                String.format("Un utilisateur avec le rôle '%s' ne peut pas avoir un manager avec le rôle '%s'. " +
                              "Managers autorisés: %s", 
                              userRole, managerRole, allowedRoles)
            );
        }
        
        // Prevent circular reference (can't be your own manager)
        if (manager.getId().equals(user.getId())) {
            throw new IllegalArgumentException("Un utilisateur ne peut pas être son propre manager");
        }
        
        // Prevent circular hierarchy (A -> B -> A)
        if (isCircularReference(user, manager)) {
            throw new IllegalArgumentException("La hiérarchie ne peut pas créer une référence circulaire");
        }
    }
    
    /**
     * Checks if assigning this manager would create a circular reference
     */
    private boolean isCircularReference(AppUser user, AppUser potentialManager) {
        Set<Long> visited = new HashSet<>();
        AppUser current = potentialManager;
        
        while (current != null && !visited.contains(current.getId())) {
            if (current.getId().equals(user.getId())) {
                return true; // Found circular reference
            }
            visited.add(current.getId());
            current = current.getManager();
        }
        return false;
    }
    
    /**
     * Validates that a user can be assigned as a manager
     */
    private void validateManagerCapability(AppUser potentialManager) {
        String managerRole = potentialManager.getRole().getName();
        
        // Check if this role can be a manager (can have subordinates)
        boolean canBeManager = ALLOWED_MANAGER_ROLES.entrySet().stream()
            .anyMatch(entry -> entry.getValue().contains(managerRole));
        
        if (!canBeManager) {
            throw new IllegalArgumentException(
                String.format("Le rôle '%s' ne peut pas être manager. Seuls les rôles suivants peuvent être managers: %s",
                              managerRole, getAllRolesThatCanBeManagers())
            );
        }
    }
    
    /**
     * Get all roles that can be managers (roles that appear in allowed manager lists)
     */
    private List<String> getAllRolesThatCanBeManagers() {
        Set<String> managerRoles = new HashSet<>();
        for (List<String> allowedManagers : ALLOWED_MANAGER_ROLES.values()) {
            managerRoles.addAll(allowedManagers);
        }
        return new ArrayList<>(managerRoles);
    }
    
    /**
     * Get the role hierarchy chain for a user
     */
    public List<String> getRoleHierarchy(Long userId) {
        AppUser user = getById(userId);
        List<String> hierarchy = new ArrayList<>();
        AppUser current = user;
        
        hierarchy.add(current.getRole().getName());
        
        while (current.getManager() != null) {
            current = current.getManager();
            hierarchy.add(current.getRole().getName());
        }
        
        return hierarchy;
    }
    
    /**
     * Get the full manager chain for a user
     */
    public List<AppUser> getManagerChain(Long userId) {
        AppUser user = getById(userId);
        List<AppUser> chain = new ArrayList<>();
        AppUser current = user.getManager();
        
        while (current != null) {
            chain.add(current);
            current = current.getManager();
        }
        
        return chain;
    }
    
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
    
    /**
     * Get all users that can be managers (roles that can have subordinates)
     */
    public List<AppUser> getAvailableManagers() {
        List<String> managerRoles = getAllRolesThatCanBeManagers();
        return appUserRepository.findByRoleNames(managerRoles);
    }
    
    /**
     * Get manager for a user based on role hierarchy
     */
    public AppUser getManagerForUser(AppUser user) {
        if (user.getManager() != null) {
            return user.getManager();
        }
        
        // Fallback: determine manager based on role hierarchy
        String roleName = user.getRole().getName();
        List<String> allowedManagers = ALLOWED_MANAGER_ROLES.getOrDefault(roleName, Collections.emptyList());
        
        // Try to find a manager with the first allowed role
        for (String managerRole : allowedManagers) {
            Optional<AppUser> manager = appUserRepository.findFirstByRoleName(managerRole);
            if (manager.isPresent()) {
                return manager.get();
            }
        }
        
        return null;
    }
    
    // ==================== CREATE METHOD ====================
    @Transactional
    public AppUser createUser(String email, String password, String firstName,
            String lastName, String phoneNumber,
            LocalDate dateOfBirth, String address,
            Long roleId, Boolean isActive, Long managerId) {

        if (appUserRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email déjà existant: " + email);
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + roleId));

        String keycloakId = keycloakService.createUser(email, firstName, lastName, password, role.getName());

        boolean active = isActive != null ? isActive : true;
        
        AppUser.AppUserBuilder builder = AppUser.builder()
                .uuid(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .phoneNumber(phoneNumber)
                .dateOfBirth(dateOfBirth)
                .address(address)
                .role(role)
                .isActive(active);
        
        // Set manager if provided
        if (managerId != null) {
            AppUser manager = getById(managerId);
            // Validate manager assignment
            validateManagerCapability(manager);
            validateManagerAssignment(builder.build(), manager);
            builder.manager(manager);
        } else {
            // Auto-assign manager based on role
            AppUser autoManager = getManagerForRole(role.getName());
            if (autoManager != null) {
                builder.manager(autoManager);
            }
        }

        AppUser savedUser = appUserRepository.save(builder.build());

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
    
    /**
     * Get default manager for a role
     */
    private AppUser getManagerForRole(String roleName) {
        List<String> allowedManagers = ALLOWED_MANAGER_ROLES.getOrDefault(roleName, Collections.emptyList());
        
        for (String managerRole : allowedManagers) {
            Optional<AppUser> manager = appUserRepository.findFirstByRoleName(managerRole);
            if (manager.isPresent()) {
                return manager.get();
            }
        }
        return null;
    }

    // ==================== UPDATE METHOD ====================
    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName, String email,
            String phoneNumber, String address, LocalDate dateOfBirth, 
            Long roleId, Boolean isActive, Long managerId) {

        AppUser user = getById(id);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        boolean emailChanged = !user.getEmail().equals(email);
        boolean roleChanged = roleId != null && !user.getRole().getId().equals(roleId);
        boolean activeChanged = isActive != null && user.getIsActive() != isActive;
        boolean managerChanged = managerId != null && (user.getManager() == null || !user.getManager().getId().equals(managerId));

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

        // 3. Gérer le changement de manager si nécessaire
        if (managerChanged) {
            AppUser newManager = getById(managerId);
            validateManagerCapability(newManager);
            validateManagerAssignment(user, newManager);
            user.setManager(newManager);
        } else if (managerId == null && roleChanged) {
            // If role changed and no manager specified, auto-assign manager
            AppUser autoManager = getManagerForRole(user.getRole().getName());
            if (autoManager != null) {
                user.setManager(autoManager);
            }
        }

        // 4. Gérer le changement d'activation si nécessaire
        if (activeChanged) {
            if (isActive) {
                keycloakService.enableUser(mapping.getKeycloakId());
            } else {
                keycloakService.disableUser(mapping.getKeycloakId());
            }
            user.setIsActive(isActive);
        }

        // 5. Mettre à jour les champs de l'utilisateur
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        user.setPhoneNumber(phoneNumber);
        user.setAddress(address);
        user.setDateOfBirth(dateOfBirth);

        // 6. Mettre à jour le mapping Keycloak
        mapping.setEmail(email);
        mapping.setUsername(email);
        mapping.setLastSync(LocalDateTime.now());
        keycloakMappingRepository.save(mapping);

        // 7. UN SEUL SAVE à la fin
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
        
        // Auto-assign manager based on new role
        AppUser newManager = getManagerForRole(newRole.getName());
        if (newManager != null) {
            validateManagerCapability(newManager);
            validateManagerAssignment(user, newManager);
            user.setManager(newManager);
        } else {
            user.setManager(null);
        }
        
        return appUserRepository.save(user);
    }
    
    @Transactional
    public AppUser changeManager(Long id, Long managerId) {
        AppUser user = getById(id);
        
        if (managerId == null) {
            user.setManager(null);
        } else {
            AppUser newManager = getById(managerId);
            
            // Validate manager capability
            validateManagerCapability(newManager);
            
            // Validate manager assignment
            validateManagerAssignment(user, newManager);
            
            user.setManager(newManager);
        }
        
        return appUserRepository.save(user);
    }
    
    /**
     * Get all subordinates of a manager
     */
    public List<AppUser> getSubordinates(Long managerId) {
        return appUserRepository.findByManagerId(managerId);
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