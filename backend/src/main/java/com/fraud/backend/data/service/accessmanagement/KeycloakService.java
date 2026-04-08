package com.fraud.backend.data.service.accessmanagement;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import com.fraud.backend.config.keycloak.KeycloakProperties;
import com.fraud.backend.data.service.accessmanagement.authverification.OtpCacheService;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakService {

    private final Keycloak keycloak;
    private final KeycloakProperties keycloakProperties;
    private final EmailService emailService;
    private final OtpCacheService otpCacheService;  // Use centralized OTP cache

    public RealmResource getRealm() {
        return keycloak.realm(keycloakProperties.getRealm());
    }

    // ==================== CREATE USER ====================
    public String createUser(String email, String firstName, String lastName, String password, String role) {
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);
        user.setEmail(email);
        user.setUsername(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmailVerified(false);  // Email NOT verified initially

        try (Response response = getRealm().users().create(user)) {
            if (response.getStatus() != 201) {
                throw new RuntimeException("Failed to create user in Keycloak, status: " + response.getStatus());
            }

            String locationPath = response.getLocation().getPath();
            String keycloakId = locationPath.substring(locationPath.lastIndexOf('/') + 1);

            setPassword(keycloakId, password);
            assignRole(keycloakId, role);

            log.info("User created in Keycloak with id: {} (email verification pending)", keycloakId);
            return keycloakId;
        }
    }

    // Set password
    public void setPassword(String keycloakId, String newPassword) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(newPassword);
        credential.setTemporary(false);
        getRealm().users().get(keycloakId).resetPassword(credential);
        log.info("Password set for user with id: {}", keycloakId);
    }

    // Assign role
    public void assignRole(String keycloakId, String roleName) {
        RoleRepresentation role = getRealm().roles().get(roleName).toRepresentation();
        getRealm().users().get(keycloakId).roles().realmLevel().add(List.of(role));
    }

    // Remove role
    public void removeRole(String keycloakId, String roleName) {
        RoleRepresentation role = getRealm().roles().get(roleName).toRepresentation();
        getRealm().users().get(keycloakId).roles().realmLevel().remove(List.of(role));
        log.info("Role {} removed from Keycloak user: {}", roleName, keycloakId);
    }

    // Update user
    public void updateUser(String keycloakId, String firstName, String lastName, String email) {
        UserResource userResource = getRealm().users().get(keycloakId);
        UserRepresentation user = userResource.toRepresentation();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmail(email);
        user.setUsername(email);
        userResource.update(user);
        log.info("User updated in Keycloak: {}", keycloakId);
    }

    // Enable / Disable user
    public void enableUser(String keycloakId) {
        UserResource userResource = getRealm().users().get(keycloakId);
        UserRepresentation user = userResource.toRepresentation();
        user.setEnabled(true);
        userResource.update(user);
        log.info("User enabled in Keycloak: {}", keycloakId);
    }

    public void disableUser(String keycloakId) {
        UserResource userResource = getRealm().users().get(keycloakId);
        UserRepresentation user = userResource.toRepresentation();
        user.setEnabled(false);
        userResource.update(user);
        log.info("User disabled in Keycloak: {}", keycloakId);
    }

    // Delete user
    public void deleteUser(String keycloakId) {
        getRealm().users().get(keycloakId).remove();
        log.info("User deleted from Keycloak: {}", keycloakId);
    }

    // ==================== EMAIL VERIFICATION WITH OTP ====================
    
    /**
     * Verify OTP code - Delegates to centralized OtpCacheService
     */
    public boolean verifyOTP(String email, String code) {
        boolean isValid = otpCacheService.verifyOtp(email, code);
        if (isValid) {
            log.info("OTP verified successfully for email: {}", email);
        } else {
            log.warn("Invalid OTP attempt for email: {}", email);
        }
        return isValid;
    }
    
    /**
     * Check if user's email is verified in Keycloak
     */
    public boolean isEmailVerified(String keycloakUserId) {
        try {
            UserRepresentation user = getRealm().users().get(keycloakUserId).toRepresentation();
            boolean verified = user.isEmailVerified() != null && user.isEmailVerified();
            log.debug("Email verification status for {}: {}", keycloakUserId, verified);
            return verified;
        } catch (Exception e) {
            log.error("Failed to check email verification status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if user's email is verified by email address
     */
    public boolean isEmailVerifiedByEmail(String email) {
        try {
            List<UserRepresentation> users = getRealm().users().search(email, true);
            if (users.isEmpty()) {
                return false;
            }
            UserRepresentation user = users.get(0);
            boolean verified = user.isEmailVerified() != null && user.isEmailVerified();
            log.debug("Email verification status for {}: {}", email, verified);
            return verified;
        } catch (Exception e) {
            log.error("Failed to check email verification status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Manually mark email as verified in Keycloak
     */
    public void markEmailAsVerified(String keycloakUserId, String email, String firstName) {
        try {
            UserResource userResource = getRealm().users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();
            user.setEmailVerified(true);
            // Remove the VERIFY_EMAIL required action
            List<String> requiredActions = user.getRequiredActions();
            if (requiredActions != null) {
                requiredActions.remove("VERIFY_EMAIL");
                user.setRequiredActions(requiredActions);
            }
            userResource.update(user);
            
            // Send verification success email
            try {
                emailService.sendVerificationSuccessEmail(email, firstName);
                log.info("Verification success email sent to: {}", email);
            } catch (Exception e) {
                log.warn("Failed to send success email to {}: {}", email, e.getMessage());
            }
            
            log.info("Email successfully marked as verified for user: {}", keycloakUserId);
        } catch (Exception e) {
            log.error("Failed to mark email as verified for {}: {}", keycloakUserId, e.getMessage());
            throw new RuntimeException("Erreur lors de la vérification de l'email", e);
        }
    }

    /**
     * Find Keycloak user ID by email
     */
    public String findKeycloakIdByEmail(String email) {
        try {
            List<UserRepresentation> users = getRealm().users().search(email, true);
            if (users.isEmpty()) {
                return null;
            }
            return users.get(0).getId();
        } catch (Exception e) {
            log.error("Failed to find Keycloak user by email {}: {}", email, e.getMessage());
            return null;
        }
    }

    /**
     * Get user's first name by email
     */
    public String getUserFirstNameByEmail(String email) {
        try {
            List<UserRepresentation> users = getRealm().users().search(email, true);
            if (!users.isEmpty()) {
                return users.get(0).getFirstName();
            }
        } catch (Exception e) {
            log.warn("Could not find user with email: {}", email);
        }
        return "User"; // Default fallback
    }

    // ==================== ROLE MANAGEMENT ====================
    public String createRole(String roleName, String description) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(roleName);
        role.setDescription(description);
        getRealm().roles().create(role);
        return roleName;
    }

    public void updateRole(String roleName, String description) {
        RoleRepresentation role = getRealm().roles().get(roleName).toRepresentation();
        role.setDescription(description);
        getRealm().roles().get(roleName).update(role);
        log.info("Role updated in Keycloak: {}", roleName);
    }

    public void deleteRole(String roleName) {
        getRealm().roles().deleteRole(roleName);
        log.info("Role deleted from Keycloak: {}", roleName);
    }

    public List<RoleRepresentation> getAllRoles() {
        return getRealm().roles().list();
    }

    public RoleRepresentation getRole(String roleName) {
        return getRealm().roles().get(roleName).toRepresentation();
    }

    // ==================== REALM SETTINGS ====================
    public void updateSessionTimeout(int timeoutMinutes) {
        RealmRepresentation realm = getRealm().toRepresentation();
        int seconds = timeoutMinutes * 60;
        realm.setSsoSessionIdleTimeout(seconds);
        realm.setSsoSessionMaxLifespan(seconds * 2);
        realm.setAccessTokenLifespan(seconds);
        getRealm().update(realm);
        log.info("Keycloak session timeout updated to {} minutes", timeoutMinutes);
    }

    public void updateMaxLoginAttempts(int maxAttempts) {
        RealmRepresentation realm = getRealm().toRepresentation();
        realm.setBruteForceProtected(true);
        realm.setFailureFactor(maxAttempts);
        getRealm().update(realm);
        log.info("Keycloak max login attempts updated to {}", maxAttempts);
    }

    public void updatePasswordExpiryDays(int days) {
        RealmRepresentation realm = getRealm().toRepresentation();
        String currentPolicy = realm.getPasswordPolicy();

        String cleaned = currentPolicy == null ? ""
                : currentPolicy.replaceAll("forceExpiredPasswordChange\\(\\d+\\)", "").trim();

        String newPolicy = cleaned.isEmpty()
                ? "forceExpiredPasswordChange(" + days + ")"
                : cleaned + " and forceExpiredPasswordChange(" + days + ")";

        realm.setPasswordPolicy(newPolicy);
        getRealm().update(realm);
        log.info("Keycloak password expiry updated to {} days", days);
    }
}