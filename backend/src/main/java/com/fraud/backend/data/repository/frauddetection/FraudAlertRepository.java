package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long>, JpaSpecificationExecutor<FraudAlert> {
    
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
    
    long count();
    long countByRiskLevel(String riskLevel);
    long countByStatus(String status);
    
    @EntityGraph(attributePaths = {"transaction", "user", "modelUsed"})
    List<FraudAlert> findTop8ByOrderByCreatedAtDesc();
    
    @EntityGraph(attributePaths = {"transaction", "user", "modelUsed"})
    List<FraudAlert> findByRiskLevelInAndStatusOrderByFraudScoreDesc(List<String> riskLevels, String status, Pageable pageable);
    
    @Query("SELECT FUNCTION('DATE', f.createdAt) as date, COUNT(f) as count, " +
           "SUM(CASE WHEN f.riskLevel = 'CRITICAL' THEN 1 ELSE 0 END) as criticalCount, " +
           "SUM(CASE WHEN f.riskLevel = 'HIGH' THEN 1 ELSE 0 END) as highCount, " +
           "SUM(CASE WHEN f.riskLevel = 'MEDIUM' THEN 1 ELSE 0 END) as mediumCount, " +
           "SUM(CASE WHEN f.riskLevel = 'LOW' THEN 1 ELSE 0 END) as lowCount " +
           "FROM FraudAlert f " +
           "WHERE f.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY FUNCTION('DATE', f.createdAt) " +
           "ORDER BY FUNCTION('DATE', f.createdAt)")
    List<Object[]> getTimeSeriesData(@Param("startDate") LocalDateTime startDate, 
                                      @Param("endDate") LocalDateTime endDate);
    
    @Query("SELECT f.riskLevel, COUNT(f), " +
           "CAST(COUNT(f) * 100.0 / (SELECT COUNT(*) FROM FraudAlert f2 WHERE f2.createdAt BETWEEN :startDate AND :endDate) AS double) " +
           "FROM FraudAlert f " +
           "WHERE f.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY f.riskLevel")
    List<Object[]> getRiskDistribution(@Param("startDate") LocalDateTime startDate,
                                        @Param("endDate") LocalDateTime endDate);
    
    @Query("SELECT COUNT(f), " +
           "SUM(CASE WHEN f.riskLevel = 'CRITICAL' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN f.riskLevel = 'HIGH' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN f.riskLevel = 'MEDIUM' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN f.riskLevel = 'LOW' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN f.status = 'PENDING' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN f.status = 'REVIEWED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN f.status = 'RESOLVED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN f.status = 'FALSE_POSITIVE' THEN 1 ELSE 0 END) " +
           "FROM FraudAlert f")
    List<Object[]> getAlertStats();
}