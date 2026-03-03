package com.fraud.backend.data.service.accessmanagement;

import com.fraud.backend.config.KeycloakProperties;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakService {

    private final Keycloak keycloak;
    private final KeycloakProperties keycloakProperties;

    // Realm Ressource
    private RealmResource getRealm() {
        return keycloak.realm(keycloakProperties.getRealm());
    }

    // Creation user
    public String createUser(String email, String firstName, String lastName, String password, String role) {
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);
        user.setEmail(email);
        user.setUsername(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmailVerified(true);

        try (Response response = getRealm().users().create(user)) {
            if (response.getStatus() != 201) {
                throw new RuntimeException("Failed to create user in Keycloak, status: " + response.getStatus());
            }

            String locationPath = response.getLocation().getPath();
            String keycloakId = locationPath.substring(locationPath.lastIndexOf('/') + 1);

            setPassword(keycloakId, password);
            assignRole(keycloakId, role);

            log.info("User created in Keycloak with id: {}", keycloakId);
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

    // supprimer role
    public void removeRole(String keycloakId, String roleName) {
        RoleRepresentation role = getRealm().roles().get(roleName).toRepresentation();
        getRealm().users().get(keycloakId).roles().realmLevel().remove(List.of(role));
        log.info("Role {} removed from Keycloak user: {}", roleName, keycloakId);
    }

    // modifier utilisateur
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

    // activer ou desactiver utilisateur
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

    // Supprimer utilisateur
    public void deleteUser(String keycloakId) {
        getRealm().users().get(keycloakId).remove();
        log.info("User deleted from Keycloak: {}", keycloakId);
    }



    // ========== Role Management Methods ==========

    // Création de rôle dans Keycloak
    public String createRole(String roleName, String description) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(roleName);
        role.setDescription(description);
        
        getRealm().roles().create(role);
        
        // Trouver l'ID du rôle créé
        RoleRepresentation createdRole = getRealm().roles().get(roleName).toRepresentation();
        
        return createdRole.getName();
    }

    // Mise à jour de rôle dans Keycloak
    public void updateRole(String roleName, String description) {
        RoleRepresentation role = getRealm().roles().get(roleName).toRepresentation();
        role.setDescription(description);
        getRealm().roles().get(roleName).update(role);
        log.info("Role updated in Keycloak: {}", roleName);
    }

    // Suppression de rôle dans Keycloak
    public void deleteRole(String roleName) {
        getRealm().roles().deleteRole(roleName);
        log.info("Role deleted from Keycloak: {}", roleName);
    }

    // Trouver tous les rôles
    public List<RoleRepresentation> getAllRoles() {
        return getRealm().roles().list();
    }

    // Trouver un rôle par son nom
    public RoleRepresentation getRole(String roleName) {
        return getRealm().roles().get(roleName).toRepresentation();
    }
}