package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KeycloakMappingRepository extends JpaRepository<KeycloakMapping, Long> {
    Optional<KeycloakMapping> findByKeycloakId(String keycloakId);
    Optional<KeycloakMapping> findByUserId(Long userId);
    Optional<KeycloakMapping> findByEmail(String email);
}