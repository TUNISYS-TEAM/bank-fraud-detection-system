package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModelDeploymentHistoryRepository extends JpaRepository<ModelDeploymentHistory, Long> {
    
    Optional<ModelDeploymentHistory> findFirstByStatusOrderByDeploymentDateDesc(String status);
    
    Optional<ModelDeploymentHistory> findFirstByModel_ModelUuidAndStatusOrderByDeploymentDateDesc(UUID modelUuid, String status);
    
    List<ModelDeploymentHistory> findAllByOrderByDeploymentDateDesc();
}