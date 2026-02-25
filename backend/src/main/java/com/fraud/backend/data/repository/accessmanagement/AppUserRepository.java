package com.fraud.backend.data.repository.accessmanagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fraud.backend.data.entity.accessmanagement.AppUser;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByUuid(UUID uuid);

    boolean existsByEmail(String email);

    Page<AppUser> findByRole(String role, Pageable pageable);

}