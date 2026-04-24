package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long>, JpaSpecificationExecutor<FraudAlert> {

    @Override
    @EntityGraph(attributePaths = {
            "transaction", "transaction.account", "transaction.account.client",
            "transaction.dataset", "transaction.dataset.assignedManager",
            "user", "modelUsed", "reviewedBy", "assignedAnalyst"
    })
    Page<FraudAlert> findAll(@Nullable Specification<FraudAlert> spec, Pageable pageable);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed" })
    Optional<FraudAlert> findByAlertUuid(UUID alertUuid);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed" })
    Page<FraudAlert> findByStatus(String status, Pageable pageable);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed" })
    Page<FraudAlert> findByRiskLevel(String riskLevel, Pageable pageable);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed" })
    List<FraudAlert> findByTransaction_Account_IdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed" })
    Page<FraudAlert> findByModelUsed(ModelML model, Pageable pageable);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed" })
    @Query("SELECT f FROM FraudAlert f WHERE f.modelUsed.modelUuid = :modelUuid")
    Page<FraudAlert> findByModelUsedUuid(@Param("modelUuid") UUID modelUuid, Pageable pageable);

    @Query("SELECT f FROM FraudAlert f WHERE f.modelUsed = :model AND f.createdAt > :since")
    List<FraudAlert> findByModelUsedAndCreatedAtAfter(@Param("model") ModelML model,
            @Param("since") LocalDateTime since);

    @Query("SELECT f FROM FraudAlert f WHERE f.modelUsed.modelUuid = :modelUuid AND f.createdAt > :since")
    List<FraudAlert> findByModelUsedUuidAndCreatedAtAfter(@Param("modelUuid") UUID modelUuid,
            @Param("since") LocalDateTime since);

    long count();

    long countByRiskLevel(String riskLevel);

    long countByStatus(String status);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed" })
    List<FraudAlert> findByRiskLevelInAndStatusOrderByFraudScoreDesc(List<String> riskLevels, String status,
            Pageable pageable);

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
            "CAST(COUNT(f) * 100.0 / (SELECT COUNT(*) FROM FraudAlert f2 WHERE f2.createdAt BETWEEN :startDate AND :endDate) AS double) "
            +
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
            "SUM(CASE WHEN f.status = 'CONFIRMED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'FALSE_POSITIVE' THEN 1 ELSE 0 END) " +
            "FROM FraudAlert f")
    List<Object[]> getAlertStats();

    // =========================================================
    // MANAGER-FILTERED QUERIES (Team Isolation)
    // =========================================================

    @Query("SELECT COUNT(f), " +
            "SUM(CASE WHEN f.riskLevel = 'CRITICAL' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.riskLevel = 'HIGH' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.riskLevel = 'MEDIUM' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.riskLevel = 'LOW' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'PENDING' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'REVIEWED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'CONFIRMED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'FALSE_POSITIVE' THEN 1 ELSE 0 END) " +
            "FROM FraudAlert f " +
            "LEFT JOIN f.transaction t " +
            "LEFT JOIN t.dataset d " +
            "LEFT JOIN d.assignedManager m " +
            "WHERE m.id = :managerId")
    List<Object[]> getAlertStatsByManagerId(@Param("managerId") Long managerId);

    @Query("SELECT FUNCTION('DATE', f.createdAt) as date, COUNT(f) as count, " +
            "SUM(CASE WHEN f.riskLevel = 'CRITICAL' THEN 1 ELSE 0 END) as criticalCount, " +
            "SUM(CASE WHEN f.riskLevel = 'HIGH' THEN 1 ELSE 0 END) as highCount, " +
            "SUM(CASE WHEN f.riskLevel = 'MEDIUM' THEN 1 ELSE 0 END) as mediumCount, " +
            "SUM(CASE WHEN f.riskLevel = 'LOW' THEN 1 ELSE 0 END) as lowCount " +
            "FROM FraudAlert f " +
            "LEFT JOIN f.transaction t " +
            "LEFT JOIN t.dataset d " +
            "LEFT JOIN d.assignedManager m " +
            "WHERE f.createdAt BETWEEN :startDate AND :endDate " +
            "AND m.id = :managerId " +
            "GROUP BY FUNCTION('DATE', f.createdAt) " +
            "ORDER BY FUNCTION('DATE', f.createdAt)")
    List<Object[]> getTimeSeriesDataByManagerId(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("managerId") Long managerId);

    @Query("SELECT f.riskLevel, COUNT(f), " +
            "CAST(COUNT(f) * 100.0 / (SELECT COUNT(f2) FROM FraudAlert f2 LEFT JOIN f2.transaction t2 LEFT JOIN t2.dataset d2 LEFT JOIN d2.assignedManager m2 WHERE f2.createdAt BETWEEN :startDate AND :endDate AND m2.id = :managerId) AS double) "
            +
            "FROM FraudAlert f " +
            "LEFT JOIN f.transaction t " +
            "LEFT JOIN t.dataset d " +
            "LEFT JOIN d.assignedManager m " +
            "WHERE f.createdAt BETWEEN :startDate AND :endDate " +
            "AND m.id = :managerId " +
            "GROUP BY f.riskLevel")
    List<Object[]> getRiskDistributionByManagerId(@Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("managerId") Long managerId);

    @EntityGraph(attributePaths = { "transaction", "user", "modelUsed" })
    @Query("SELECT f FROM FraudAlert f " +
            "LEFT JOIN f.transaction t " +
            "LEFT JOIN t.dataset d " +
            "LEFT JOIN d.assignedManager m " +
            "WHERE m.id = :managerId ORDER BY " +
            "CASE WHEN f.status = 'PENDING' THEN 0 WHEN f.status = 'REVIEWED' THEN 1 ELSE 2 END ASC, " +
            "CASE WHEN f.riskLevel = 'CRITICAL' THEN 0 WHEN f.riskLevel = 'HIGH' THEN 1 WHEN f.riskLevel = 'MEDIUM' THEN 2 WHEN f.riskLevel = 'LOW' THEN 3 ELSE 4 END ASC, "
            +
            "f.createdAt DESC")
    List<FraudAlert> findPrioritizedAnomaliesByManagerId(@Param("managerId") Long managerId, Pageable pageable);

    @EntityGraph(attributePaths = { "transaction", "user", "modelUsed" })
    @Query("SELECT f FROM FraudAlert f ORDER BY " +
            "CASE WHEN f.status = 'PENDING' THEN 0 WHEN f.status = 'REVIEWED' THEN 1 ELSE 2 END ASC, " +
            "CASE WHEN f.riskLevel = 'CRITICAL' THEN 0 WHEN f.riskLevel = 'HIGH' THEN 1 WHEN f.riskLevel = 'MEDIUM' THEN 2 WHEN f.riskLevel = 'LOW' THEN 3 ELSE 4 END ASC, "
            +
            "f.createdAt DESC")
    List<FraudAlert> findPrioritizedAnomalies(Pageable pageable);

    @Query("SELECT f FROM FraudAlert f " +
            "LEFT JOIN f.transaction t " +
            "LEFT JOIN t.dataset d " +
            "LEFT JOIN d.assignedManager m " +
            "WHERE m.id = :managerId")
    Page<FraudAlert> findAllByManagerId(@Param("managerId") Long managerId, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM FraudAlert f WHERE f.modelUsed.modelUuid = :modelUuid")
    void deleteByModelUsed_ModelUuid(@Param("modelUuid") UUID modelUuid);

    @Query(value = "SELECT COUNT(f), " +
            "SUM(CASE WHEN f.risk_level = 'CRITICAL' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.risk_level = 'HIGH' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.risk_level = 'MEDIUM' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.risk_level = 'LOW' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'PENDING' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'REVIEWED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'CONFIRMED' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN f.status = 'FALSE_POSITIVE' THEN 1 ELSE 0 END) " +
            "FROM fraud_alert f " +
            "LEFT JOIN transaction t ON f.transaction_id = t.id " +
            "LEFT JOIN dataset d ON t.dataset_id = d.id " +
            "WHERE (:managerId IS NULL OR d.assigned_manager_id = :managerId) " +
            "AND (:datasetUuid IS NULL OR CAST(d.dataset_uuid AS VARCHAR) = CAST(:datasetUuid AS VARCHAR))", nativeQuery = true)
    List<Object[]> getAlertStatsFiltered(@Param("managerId") Long managerId,
            @Param("datasetUuid") String datasetUuid);

    // =========================================================
    // ANALYST ASSIGNMENT QUERIES
    // =========================================================

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed", "assignedAnalyst" })
    Page<FraudAlert> findByAssignedAnalyst_Id(Long analystId, Pageable pageable);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.account.client", "user",
            "modelUsed", "assignedAnalyst" })
    List<FraudAlert> findByAssignedAnalyst_Id(Long analystId);

    @Modifying
    @Transactional
    @Query("UPDATE FraudAlert f SET f.assignedAnalyst = :analyst WHERE f.alertUuid IN :alertUuids")
    int bulkAssignAlerts(@Param("alertUuids") List<UUID> alertUuids, @Param("analyst") AppUser analyst);

    @Query("SELECT COUNT(f) FROM FraudAlert f WHERE f.assignedAnalyst.id = :analystId")
    long countByAssignedAnalystId(@Param("analystId") Long analystId);

    @Query("SELECT COUNT(f) FROM FraudAlert f " +
            "LEFT JOIN f.transaction t " +
            "LEFT JOIN t.dataset d " +
            "LEFT JOIN d.assignedManager m " +
            "WHERE m.id = :managerId AND f.assignedAnalyst IS NULL")
    long countUnassignedByManagerId(@Param("managerId") Long managerId);

    @Modifying
    @Transactional
    @Query("UPDATE FraudAlert f SET f.assignedAnalyst = NULL WHERE f.alertUuid IN :alertUuids")
    int bulkUnassignAlerts(@Param("alertUuids") List<UUID> alertUuids);

    @Query(value = """
            SELECT
                t.postal_code_client as postalCode,
                COUNT(fa.id) as fraudCount
            FROM fraud_alert fa
            JOIN transaction t ON fa.transaction_id = t.id
            LEFT JOIN dataset d ON t.dataset_id = d.id
            WHERE fa.status = 'CONFIRMED'
              AND fa.created_at >= :startDate
              AND fa.created_at <= :endDate
              AND (:managerId IS NULL OR d.assigned_manager_id = :managerId)
            GROUP BY t.postal_code_client
            HAVING COUNT(fa.id) > 0
            ORDER BY fraudCount DESC
            """, nativeQuery = true)
    List<Object[]> getFraudLocationsByPostalCode(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("managerId") Long managerId);

    @EntityGraph(attributePaths = { "transaction", "transaction.account", "transaction.dataset" })
    List<FraudAlert> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}