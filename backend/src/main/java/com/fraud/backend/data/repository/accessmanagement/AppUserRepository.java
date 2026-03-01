package com.fraud.backend.data.repository.accessmanagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.fraud.backend.data.entity.accessmanagement.AppUser;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByUuid(UUID uuid);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM AppUser u WHERE u.role.id = :roleId")
    Page<AppUser> findByRoleId(@Param("roleId") Long roleId, Pageable pageable);

    @Query("SELECT u FROM AppUser u LEFT JOIN FETCH u.role WHERE u.id = :id")
    Optional<AppUser> findByIdWithRole(@Param("id") Long id);
}