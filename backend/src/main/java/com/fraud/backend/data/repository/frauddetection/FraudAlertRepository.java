package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {
    
    @EntityGraph(attributePaths = {"transaction", "user", "modelUsed"})
    Optional<FraudAlert> findByAlertUuid(UUID alertUuid);
    
    @EntityGraph(attributePaths = {"transaction", "user", "modelUsed"})
    Page<FraudAlert> findByStatus(String status, Pageable pageable);
    
    @EntityGraph(attributePaths = {"transaction", "user", "modelUsed"})
    Page<FraudAlert> findByRiskLevel(String riskLevel, Pageable pageable);
    
    @EntityGraph(attributePaths = {"transaction", "user", "modelUsed"})
    List<FraudAlert> findByTransaction_Account_IdOrderByCreatedAtDesc(Long accountId, Pageable pageable);
    
    @EntityGraph(attributePaths = {"transaction", "user", "modelUsed"})
    Page<FraudAlert> findByModelUsed_ModelUuid(UUID modelUuid, Pageable pageable);
}