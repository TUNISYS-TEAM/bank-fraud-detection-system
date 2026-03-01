package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    
    Optional<Role> findByName(String name);
    
    boolean existsByName(String name);
    
    Optional<Role> findByKeycloakRoleName(String keycloakRoleName);
    
    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.id = :id")
    Optional<Role> findByIdWithPermissions(@Param("id") Long id);
    
    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.name = :name")
    Optional<Role> findByNameWithPermissions(@Param("name") String name);
}