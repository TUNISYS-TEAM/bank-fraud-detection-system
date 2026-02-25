package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    Optional<FraudAlert> findByAlertUuid(UUID alertUuid);

    Page<FraudAlert> findByStatus(String status, Pageable pageable);

    Page<FraudAlert> findByRiskLevel(String riskLevel, Pageable pageable);

    Page<FraudAlert> findByUser_Id(Long userId, Pageable pageable);

    Page<FraudAlert> findByCreatedAtBetween(
            LocalDateTime start,
            LocalDateTime end,
            Pageable pageable
    );
}