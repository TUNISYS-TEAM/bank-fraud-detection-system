package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.accessmanagement.RoleRepository;
import com.fraud.backend.data.repository.accessmanagement.UserSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Slf4j
@Service
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository appUserRepository;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final UserSessionRepository userSessionRepository;
    private final RoleRepository roleRepository;
    private final KeycloakService keycloakService;
    private final OtpService otpService;  // ADDED: OTP Service dependency
    private final BCryptPasswordEncoder passwordEncoder;

    // Hierarchical rules for manager assignment
    private static final Map<String, List<String>> ALLOWED_MANAGER_ROLES = new HashMap<>();

    static {
        ALLOWED_MANAGER_ROLES.put("ANALYST", Arrays.asList("MANAGER", "ADMIN"));
        ALLOWED_MANAGER_ROLES.put("MANAGER", Arrays.asList("ADMIN"));
        ALLOWED_MANAGER_ROLES.put("ADMIN", Collections.emptyList());
    }

    // Constructor with all dependencies (replaces @RequiredArgsConstructor for clarity)
    public UserService(
            AppUserRepository appUserRepository,
            KeycloakMappingRepository keycloakMappingRepository,
            UserSessionRepository userSessionRepository,
            RoleRepository roleRepository,
            KeycloakService keycloakService,
            OtpService otpService,
            BCryptPasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.keycloakMappingRepository = keycloakMappingRepository;
        this.userSessionRepository = userSessionRepository;
        this.roleRepository = roleRepository;
        this.keycloakService = keycloakService;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
    }

    public void setAllowedManagerRolesForCustomRole(String customRoleName, List<String> allowedManagerRoles) {
        ALLOWED_MANAGER_ROLES.put(customRoleName, allowedManagerRoles);
        log.info("Allowed manager roles for role '{}' set to: {}", customRoleName, allowedManagerRoles);
    }

    // ==================== VALIDATION METHODS ====================
    private void validateManagerAssignment(AppUser user, AppUser manager) {
        if (manager == null)
            return;

        String userRole = user.getRole().getName();
        String managerRole = manager.getRole().getName();

        List<String> allowedRoles = ALLOWED_MANAGER_ROLES.getOrDefault(userRole, Collections.emptyList());

        if (allowedRoles.isEmpty() && manager != null) {
            throw new IllegalArgumentException(
                    String.format("L'utilisateur avec le rôle '%s' ne peut pas avoir de manager", userRole));
        }

        if (!allowedRoles.contains(managerRole)) {
            throw new IllegalArgumentException(
                    String.format("Un utilisateur avec le rôle '%s' ne peut pas avoir un manager avec le rôle '%s'. " +
                            "Managers autorisés: %s", userRole, managerRole, allowedRoles));
        }

        if (manager.getId().equals(user.getId())) {
            throw new IllegalArgumentException("Un utilisateur ne peut pas être son propre manager");
        }

        if (isCircularReference(user, manager)) {
            throw new IllegalArgumentException("La hiérarchie ne peut pas créer une référence circulaire");
        }
    }

    private boolean isCircularReference(AppUser user, AppUser potentialManager) {
        Set<Long> visited = new HashSet<>();
        AppUser current = potentialManager;

        while (current != null && !visited.contains(current.getId())) {
            if (current.getId().equals(user.getId())) {
                return true;
            }
            visited.add(current.getId());
            current = current.getManager();
        }
        return false;
    }

    private void validateManagerCapability(AppUser potentialManager) {
        String managerRole = potentialManager.getRole().getName();

        boolean canBeManager = ALLOWED_MANAGER_ROLES.entrySet().stream()
                .anyMatch(entry -> entry.getValue().contains(managerRole));

        if (!canBeManager) {
            throw new IllegalArgumentException(
                    String.format(
                            "Le rôle '%s' ne peut pas être manager. Seuls les rôles suivants peuvent être managers: %s",
                            managerRole, getAllRolesThatCanBeManagers()));
        }
    }

    private List<String> getAllRolesThatCanBeManagers() {
        Set<String> managerRoles = new HashSet<>();
        for (List<String> allowedManagers : ALLOWED_MANAGER_ROLES.values()) {
            managerRoles.addAll(allowedManagers);
        }
        return new ArrayList<>(managerRoles);
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

    public List<AppUser> getAvailableManagers() {
        List<String> managerRoles = getAllRolesThatCanBeManagers();
        return appUserRepository.findByRoleNames(managerRoles);
    }

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

    // ==================== CREATE METHOD ====================
    @Transactional
    public AppUser createUser(String email, String password, String firstName,
            String lastName, String phoneNumber, LocalDate dateOfBirth,
            String address, Long roleId, Boolean isActive, Long managerId) {

        String normalizedPhone = normalizePhoneNumber(phoneNumber);

        if (appUserRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email déjà existant: " + email);
        }

        if (normalizedPhone != null && appUserRepository.existsByPhoneNumber(normalizedPhone)) {
            throw new IllegalArgumentException("Numéro de téléphone déjà existant: " + normalizedPhone);
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + roleId));

        // Create user in Keycloak using the keycloak role name
        String keycloakId = keycloakService.createUser(email, firstName, lastName, password, role.getName());

        try {
            boolean active = isActive != null ? isActive : true;

            AppUser user = AppUser.builder()
                    .uuid(UUID.randomUUID())
                    .email(email)
                    .passwordHash(passwordEncoder.encode(password))
                    .firstName(firstName)
                    .lastName(lastName)
                    .phoneNumber(normalizedPhone)
                    .dateOfBirth(dateOfBirth)
                    .address(address)
                    .role(role)
                    .isActive(active)
                    .build();

            // Manager assignment
            if (managerId != null) {
                AppUser manager = getById(managerId);
                validateManagerCapability(manager);
                validateManagerAssignment(user, manager);
                user.setManager(manager);
            } else {
                AppUser autoManager = getManagerForRole(role.getName());
                if (autoManager != null) {
                    user.setManager(autoManager);
                }
            }

            AppUser savedUser = appUserRepository.save(user);

            // Save Keycloak mapping
            KeycloakMapping mapping = KeycloakMapping.builder()
                    .keycloakId(keycloakId)
                    .user(savedUser)
                    .username(email)
                    .email(email)
                    .lastSync(LocalDateTime.now())
                    .build();
            keycloakMappingRepository.save(mapping);

            if (!active) {
                try {
                    keycloakService.disableUser(keycloakId);
                } catch (Exception e) {
                    log.error("Erreur lors de la désactivation dans Keycloak: {}", e.getMessage());
                }
            }

            return savedUser;
        } catch (Exception e) {
            // Rollback: delete user from Keycloak if DB save fails
            log.error("Erreur lors de la création en BD, rollback Keycloak pour l'utilisateur: {}", email, e);
            try {
                keycloakService.deleteUser(keycloakId);
                log.info("Utilisateur supprimé de Keycloak suite à l'échec BD: {}", keycloakId);
            } catch (Exception keycloakEx) {
                log.error("Échec de la suppression Keycloak lors du rollback: {}", keycloakEx.getMessage());
            }
            throw e;
        }
    }

    // ==================== EMAIL VERIFICATION METHODS (UPDATED FOR OTP) ====================

    /**
     * Called when user tries to login but email is not verified
     * This will generate and send OTP instead of Keycloak email
     */
    @Transactional
    public void sendVerificationOnLoginAttempt(String email) {
        AppUser user = getByEmail(email);

        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new IllegalArgumentException("Compte désactivé");
        }

        // Use OtpService instead of KeycloakService
        otpService.generateAndSendOtp(email, user.getFirstName(), "VERIFICATION");
        log.info("OTP sent automatically on login attempt for: {}", email);
    }

    /**
     * Verify email using OTP code (used after user enters code)
     */
    @Transactional
    public boolean verifyEmailWithOTP(String email, String otpCode) {
        // Verify OTP using KeycloakService which delegates to OtpCacheService
        if (!keycloakService.verifyOTP(email, otpCode)) {
            log.warn("Invalid OTP for email: {}", email);
            return false;
        }

        // Get user and mark email as verified in Keycloak
        AppUser user = getByEmail(email);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId()));

        keycloakService.markEmailAsVerified(mapping.getKeycloakId(), email, user.getFirstName());
        log.info("Email successfully verified via OTP for: {}", email);

        return true;
    }

    /**
     * Verify email (used after user enters code) - kept for backward compatibility
     */
    @Transactional
    public void verifyEmail(Long userId) {
        AppUser user = getById(userId);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(userId)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + userId));

        keycloakService.markEmailAsVerified(mapping.getKeycloakId(), user.getEmail(), user.getFirstName());
        log.info("Email successfully verified for user: {} ({})", user.getEmail(), userId);
    }

    /**
     * Verify email using email address (for login flow) - uses OTP verification
     */
    @Transactional
    public boolean verifyEmailByEmailWithOTP(String email, String otpCode) {
        return verifyEmailWithOTP(email, otpCode);
    }

    /**
     * Verify email using email address (for login flow) - kept for backward compatibility
     * This now uses OTP
     */
    @Transactional
    public void verifyEmailByEmail(String email) {
        // This method is kept for compatibility but should not be used directly
        // Use verifyEmailWithOTP instead
        throw new UnsupportedOperationException("Use verifyEmailWithOTP with OTP code instead");
    }

    /**
     * Resend OTP for verification
     */
    @Transactional
    public void resendVerificationEmail(Long userId) {
        AppUser user = getById(userId);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(userId)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + userId));

        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new IllegalArgumentException(
                    "Impossible d'envoyer un email de vérification à un utilisateur désactivé");
        }

        // Use OtpService instead of KeycloakService
        otpService.generateAndSendOtp(user.getEmail(), user.getFirstName(), "VERIFICATION");
        log.info("OTP resent to user: {}", user.getEmail());
    }

    // ==================== UPDATE, ROLE, MANAGER, DELETE METHODS ====================
    @Transactional
    public AppUser updateUser(Long id, String firstName, String lastName, String email,
            String phoneNumber, String address, LocalDate dateOfBirth,
            Long roleId, Boolean isActive, Long managerId) {

        String normalizedPhone = normalizePhoneNumber(phoneNumber);

        AppUser user = getById(id);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(id)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + id));

        boolean emailChanged = !user.getEmail().equals(email);

        if (emailChanged) {
            Optional<AppUser> existingUserOpt = appUserRepository.findByEmail(email);
            if (existingUserOpt.isPresent()) {
                AppUser existingUser = existingUserOpt.get();
                throw new IllegalArgumentException("Cet email existe déjà pour l'utilisateur "
                        + existingUser.getFirstName() + " " + existingUser.getLastName());
            }
        }
        boolean roleChanged = roleId != null && !user.getRole().getId().equals(roleId);
        boolean activeChanged = isActive != null && user.getIsActive() != isActive;
        boolean managerChanged = managerId != null
                && (user.getManager() == null || !user.getManager().getId().equals(managerId));

        keycloakService.updateUser(mapping.getKeycloakId(), firstName, lastName, email);

        if (roleChanged) {
            Role newRole = roleRepository.findById(roleId)
                    .orElseThrow(() -> new EntityNotFoundException("Rôle non trouvé: " + roleId));
            keycloakService.removeRole(mapping.getKeycloakId(), user.getRole().getKeycloakRoleName());
            keycloakService.assignRole(mapping.getKeycloakId(), newRole.getKeycloakRoleName());
            user.setRole(newRole);
        }

        if (managerChanged) {
            AppUser newManager = getById(managerId);
            validateManagerCapability(newManager);
            validateManagerAssignment(user, newManager);
            user.setManager(newManager);
        } else if (managerId == null && roleChanged) {
            AppUser autoManager = getManagerForRole(user.getRole().getName());
            user.setManager(autoManager);
        }

        if (activeChanged) {
            if (isActive) {
                keycloakService.enableUser(mapping.getKeycloakId());
            } else {
                keycloakService.disableUser(mapping.getKeycloakId());
            }
            user.setIsActive(isActive);
        }

        // Check phone number uniqueness if it changed
        if (normalizedPhone != null && !normalizedPhone.equals(user.getPhoneNumber())) {
            if (appUserRepository.existsByPhoneNumberAndUuidNot(normalizedPhone, user.getUuid())) {
                throw new IllegalArgumentException("Numéro de téléphone déjà existant: " + normalizedPhone);
            }
        }

        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        user.setPhoneNumber(normalizedPhone);
        user.setAddress(address);
        user.setDateOfBirth(dateOfBirth);

        mapping.setEmail(email);
        mapping.setUsername(email);
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

        keycloakService.removeRole(mapping.getKeycloakId(), user.getRole().getKeycloakRoleName());
        keycloakService.assignRole(mapping.getKeycloakId(), newRole.getKeycloakRoleName());

        user.setRole(newRole);

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
            validateManagerCapability(newManager);
            validateManagerAssignment(user, newManager);
            user.setManager(newManager);
        }

        return appUserRepository.save(user);
    }

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

    public Long getRoleIdByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getId();
    }

    // ==================== SELF-SERVICE PROFILE METHODS ====================

    /**
     * Update own profile (self-service) — only personal info, no role/active/manager changes
     */
    @Transactional
    public AppUser updateProfile(String email, String firstName, String lastName,
            String phoneNumber, String address, LocalDate dateOfBirth) {

        String normalizedPhone = normalizePhoneNumber(phoneNumber);

        AppUser user = getByEmail(email);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId()));

        // Update Keycloak (first/last name only — email stays the same)
        keycloakService.updateUser(mapping.getKeycloakId(), firstName, lastName, email);

        // Check phone number uniqueness if it changed
        if (normalizedPhone != null && !normalizedPhone.equals(user.getPhoneNumber())) {
            if (appUserRepository.existsByPhoneNumberAndUuidNot(normalizedPhone, user.getUuid())) {
                throw new IllegalArgumentException("Numéro de téléphone déjà existant: " + normalizedPhone);
            }
        }

        // Update local DB
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhoneNumber(normalizedPhone);
        user.setAddress(address);
        user.setDateOfBirth(dateOfBirth);

        // Sync keycloak mapping
        mapping.setLastSync(LocalDateTime.now());
        keycloakMappingRepository.save(mapping);

        return appUserRepository.save(user);
    }

    /**
     * Change own password (self-service) — verifies current password first
     */
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {

        AppUser user = getByEmail(email);

        // Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Le mot de passe actuel est incorrect");
        }

        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId()));

        // Update password in Keycloak
        keycloakService.setPassword(mapping.getKeycloakId(), newPassword);

        // Update password hash in local DB
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        appUserRepository.save(user);

        log.info("Password changed successfully for user: {}", email);
    }

    public String getRoleNameByUserId(Long userId) {
        AppUser user = getById(userId);
        return user.getRole().getName();
    }

    public Page<AppUser> getUsersByRoleId(Long roleId, Pageable pageable) {
        return appUserRepository.findByRoleId(roleId, pageable);
    }

    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }
        String normalized = phoneNumber.trim().replaceAll("\\s+", "");

        if (normalized.startsWith("00216")) {
            normalized = "+216" + normalized.substring(5);
        } else if (!normalized.startsWith("+216") && !normalized.startsWith("+")) {
            // Assume it's a local number, prepend +216
            normalized = "+216" + normalized;
        }
        return normalized;
    }

    public boolean isPhoneTaken(String phone, UUID excludeUuid) {
        String normalizedPhone = normalizePhoneNumber(phone);
        if (normalizedPhone == null) {
            return false;
        }
        if (excludeUuid != null) {
            return appUserRepository.existsByPhoneNumberAndUuidNot(normalizedPhone, excludeUuid);
        }
        return appUserRepository.existsByPhoneNumber(normalizedPhone);
    }

    public AppUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }

        // Utilisation directe du repository pour éviter l'exception EntityNotFoundException
        return appUserRepository.findByEmail(auth.getName()).orElse(null);
    }
}