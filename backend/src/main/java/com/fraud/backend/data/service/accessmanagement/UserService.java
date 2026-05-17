package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.accessmanagement.Role;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.accessmanagement.RoleRepository;
import com.fraud.backend.data.repository.accessmanagement.UserSessionRepository;
import com.fraud.backend.data.service.accessmanagement.authverification.OtpService;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private final EmailService emailService;
    private final OtpService otpService;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final Map<String, List<String>> ALLOWED_MANAGER_ROLES = new HashMap<>();

    static {
        ALLOWED_MANAGER_ROLES.put("ANALYST", Arrays.asList("MANAGER", "ADMIN"));
        ALLOWED_MANAGER_ROLES.put("MANAGER", Arrays.asList("ADMIN"));
        ALLOWED_MANAGER_ROLES.put("ADMIN", Collections.emptyList());
    }

    public UserService(
            AppUserRepository appUserRepository,
            KeycloakMappingRepository keycloakMappingRepository,
            UserSessionRepository userSessionRepository,
            RoleRepository roleRepository,
            KeycloakService keycloakService,
            EmailService emailService,
            OtpService otpService,
            BCryptPasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.keycloakMappingRepository = keycloakMappingRepository;
        this.userSessionRepository = userSessionRepository;
        this.roleRepository = roleRepository;
        this.keycloakService = keycloakService;
        this.emailService = emailService;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
    }

    // ==================== HIERARCHY CONFIG ====================
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
        if (allowedRoles.isEmpty()) {
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
            if (current.getId().equals(user.getId()))
                return true;
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
                    String.format("Le rôle '%s' ne peut pas être manager. Managers autorisés: %s",
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
            if (manager.isPresent())
                return manager.get();
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

            if (managerId != null) {
                AppUser manager = getById(managerId);
                validateManagerCapability(manager);
                validateManagerAssignment(user, manager);
                user.setManager(manager);
            } else {
                AppUser autoManager = getManagerForRole(role.getName());
                if (autoManager != null)
                    user.setManager(autoManager);
            }

            AppUser savedUser = appUserRepository.save(user);

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

    // ==================== EMAIL VERIFICATION (OTP) ====================
    @Transactional
    public void sendVerificationOnLoginAttempt(String email) {
        AppUser user = getByEmail(email);
        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new IllegalArgumentException("Compte désactivé");
        }
        otpService.generateAndSendOtp(email, user.getFirstName(), "VERIFICATION");
        log.info("OTP sent automatically on login attempt for: {}", email);
    }

    @Transactional
    public boolean verifyEmailWithOTP(String email, String otpCode) {
        if (!keycloakService.verifyOTP(email, otpCode)) {
            log.warn("Invalid OTP for email: {}", email);
            return false;
        }
        AppUser user = getByEmail(email);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId()));
        keycloakService.markEmailAsVerified(mapping.getKeycloakId(), email, user.getFirstName());
        log.info("Email successfully verified via OTP for: {}", email);
        return true;
    }

    @Transactional
    public void verifyEmail(Long userId) {
        AppUser user = getById(userId);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(userId)
                .orElseThrow(
                        () -> new EntityNotFoundException("Keycloak mapping non trouvé pour l'utilisateur: " + userId));
        keycloakService.markEmailAsVerified(mapping.getKeycloakId(), user.getEmail(), user.getFirstName());
        log.info("Email successfully verified for user: {} ({})", user.getEmail(), userId);
    }

    @Transactional
    public boolean verifyEmailByEmailWithOTP(String email, String otpCode) {
        return verifyEmailWithOTP(email, otpCode);
    }

    @Transactional
    public void verifyEmailByEmail(String email) {
        throw new UnsupportedOperationException("Use verifyEmailWithOTP with OTP code instead");
    }

    @Transactional
    public void resendVerificationEmail(Long userId) {
        AppUser user = getById(userId);
        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new IllegalArgumentException(
                    "Impossible d'envoyer un email de vérification à un utilisateur désactivé");
        }
        otpService.generateAndSendOtp(user.getEmail(), user.getFirstName(), "VERIFICATION");
        log.info("OTP resent to user: {}", user.getEmail());
    }

    // ==================== UPDATE / ROLE / MANAGER / DELETE ====================
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
            if (isActive)
                keycloakService.enableUser(mapping.getKeycloakId());
            else
                keycloakService.disableUser(mapping.getKeycloakId());
            user.setIsActive(isActive);
        }

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

    // ==================== SELF-SERVICE PROFILE ====================
    @Transactional
    public AppUser updateProfile(String email, String firstName, String lastName,
            String phoneNumber, String address, LocalDate dateOfBirth) {
        String normalizedPhone = normalizePhoneNumber(phoneNumber);
        AppUser user = getByEmail(email);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId()));

        keycloakService.updateUser(mapping.getKeycloakId(), firstName, lastName, email);

        if (normalizedPhone != null && !normalizedPhone.equals(user.getPhoneNumber())) {
            if (appUserRepository.existsByPhoneNumberAndUuidNot(normalizedPhone, user.getUuid())) {
                throw new IllegalArgumentException("Numéro de téléphone déjà existant: " + normalizedPhone);
            }
        }

        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPhoneNumber(normalizedPhone);
        user.setAddress(address);
        user.setDateOfBirth(dateOfBirth);

        mapping.setLastSync(LocalDateTime.now());
        keycloakMappingRepository.save(mapping);
        return appUserRepository.save(user);
    }

    // ==================== MOT DE PASSE OUBLIÉ (RÉINITIALISATION)
    // ====================
    @Transactional
    public void resetPassword(String email) {
        AppUser user = getByEmail(email);
        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new IllegalArgumentException("Le compte est désactivé, impossible de réinitialiser le mot de passe.");
        }
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId() +
                                ". L'utilisateur n'a peut-être jamais été synchronisé avec Keycloak."));

        try {
            keycloakService.getRealm().users().get(mapping.getKeycloakId()).toRepresentation();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "L'utilisateur Keycloak " + mapping.getKeycloakId() +
                            " n'existe plus. Veuillez contacter un administrateur.");
        }

        String tempPassword = generateRandomPassword();
        keycloakService.setPassword(mapping.getKeycloakId(), tempPassword);
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        appUserRepository.save(user);
        emailService.sendTemporaryPasswordEmail(email, user.getFirstName(), tempPassword);
        log.info("Mot de passe temporaire envoyé pour l'utilisateur : {}", email);
    }

    private String generateRandomPassword() {
        final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final String LOWER = "abcdefghijklmnopqrstuvwxyz";
        final String DIGITS = "0123456789";
        final String SPECIAL = "!@#$%^&*_-+=";
        final String ALL = UPPER + LOWER + DIGITS + SPECIAL;
        final int LENGTH = 20;

        SecureRandom random = new SecureRandom();
        List<Character> chars = new ArrayList<>();
        chars.add(UPPER.charAt(random.nextInt(UPPER.length())));
        chars.add(LOWER.charAt(random.nextInt(LOWER.length())));
        chars.add(DIGITS.charAt(random.nextInt(DIGITS.length())));
        chars.add(SPECIAL.charAt(random.nextInt(SPECIAL.length())));
        for (int i = 4; i < LENGTH; i++) {
            chars.add(ALL.charAt(random.nextInt(ALL.length())));
        }
        Collections.shuffle(chars, random);
        return chars.stream().map(String::valueOf).collect(Collectors.joining());
    }

    // ==================== CHANGEMENT DE MOT DE PASSE AVEC OTP ====================
    @Transactional
    public void initiatePasswordChange(String email, String currentPassword, String newPassword) {
        AppUser user = getByEmail(email);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Le mot de passe actuel est incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Le nouveau mot de passe doit être différent de l'ancien");
        }
        validatePasswordStrength(newPassword);

        emailService.sendPasswordChangeAlertEmail(email, user.getFirstName(), email);
        log.info("Security alert sent before OTP for: {}", email);

        otpService.generateAndSendOtp(email, user.getFirstName(), "PASSWORD_CHANGE");
        log.info("Password change OTP sent to: {}", email);
    }

    @Transactional
    public void confirmPasswordChange(String email, String otpCode, String newPassword) {
        if (!keycloakService.verifyOTP(email, otpCode)) {
            throw new IllegalArgumentException("Code OTP invalide ou expiré");
        }
        AppUser user = getByEmail(email);
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId()));

        keycloakService.setPassword(mapping.getKeycloakId(), newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        appUserRepository.save(user);
        emailService.sendPasswordChangeConfirmationEmail(email, user.getFirstName());
        log.info("Password changed successfully for user: {}", email);
    }

    private void validatePasswordStrength(String password) {
        if (password.length() < 8) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins 8 caractères");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins une majuscule");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins une minuscule");
        }
        if (!password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins un chiffre");
        }
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins un caractère spécial");
        }
    }

    // ==================== ANCIENNE MÉTHODE (COMPATIBILITÉ) ====================
    @Deprecated
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        AppUser user = getByEmail(email);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Le mot de passe actuel est incorrect");
        }
        KeycloakMapping mapping = keycloakMappingRepository.findByUserId(user.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Keycloak mapping non trouvé pour l'utilisateur: " + user.getId()));

        keycloakService.setPassword(mapping.getKeycloakId(), newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        appUserRepository.save(user);
        log.info("Password changed (direct) for user: {}", email);
    }

    // ==================== UTILITAIRES ====================
    public Long getRoleIdByUserId(Long userId) {
        return getById(userId).getRole().getId();
    }

    public String getRoleNameByUserId(Long userId) {
        return getById(userId).getRole().getName();
    }

    public Page<AppUser> getUsersByRoleId(Long roleId, Pageable pageable) {
        return appUserRepository.findByRoleId(roleId, pageable);
    }

    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty())
            return null;
        String normalized = phoneNumber.trim().replaceAll("\\s+", "");
        if (normalized.startsWith("00216"))
            normalized = "+216" + normalized.substring(5);
        else if (!normalized.startsWith("+216") && !normalized.startsWith("+"))
            normalized = "+216" + normalized;
        return normalized;
    }

    public boolean isPhoneTaken(String phone, UUID excludeUuid) {
        String normalized = normalizePhoneNumber(phone);
        if (normalized == null)
            return false;
        return excludeUuid != null
                ? appUserRepository.existsByPhoneNumberAndUuidNot(normalized, excludeUuid)
                : appUserRepository.existsByPhoneNumber(normalized);
    }

    public AppUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return appUserRepository.findByEmail(auth.getName()).orElse(null);
    }
}