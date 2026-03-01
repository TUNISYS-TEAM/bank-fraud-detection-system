package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByName(String name);

    List<Permission> findByNameIn(List<String> names);

    boolean existsByName(String name);
}