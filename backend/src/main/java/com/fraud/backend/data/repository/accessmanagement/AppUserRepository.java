package com.fraud.backend.data.repository.accessmanagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.fraud.backend.data.entity.accessmanagement.AppUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByUuid(UUID uuid);

    boolean existsByEmail(String email);

    boolean existsByRoleId(Long roleId);

    @Query("SELECT u FROM AppUser u WHERE u.role.id = :roleId")
    Page<AppUser> findByRoleId(@Param("roleId") Long roleId, Pageable pageable);

    @Query("SELECT u FROM AppUser u LEFT JOIN FETCH u.role WHERE u.id = :id")
    Optional<AppUser> findByIdWithRole(@Param("id") Long id);

    long countByIsActiveTrue();
    
    // Manager relationship queries
    @Query("SELECT u FROM AppUser u WHERE u.manager.id = :managerId")
    List<AppUser> findByManagerId(@Param("managerId") Long managerId);
    
    @Query("SELECT u FROM AppUser u WHERE u.role.name IN :roleNames AND u.isActive = true")
    List<AppUser> findByRoleNames(@Param("roleNames") List<String> roleNames);
    
    @Query("SELECT u FROM AppUser u WHERE u.role.name = :roleName AND u.isActive = true")
    Optional<AppUser> findFirstByRoleName(@Param("roleName") String roleName);
    
    @Query("SELECT u FROM AppUser u WHERE u.manager.id = :managerId AND u.isActive = true")
    List<AppUser> findActiveSubordinates(@Param("managerId") Long managerId);
}