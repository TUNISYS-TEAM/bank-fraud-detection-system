package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.enums.transactions.TransactionChannel;
import com.fraud.backend.data.enums.transactions.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByAccountId(Long accountId, Pageable pageable);

        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByIsFraudTrue(Pageable pageable);

        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByFraudScoreGreaterThan(Double threshold, Pageable pageable);

        @EntityGraph(attributePaths = { "account", "account.client", "model" })
        Page<Transaction> findByFraudScoreIsNotNull(Pageable pageable);

        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByAccountIdAndTransactionDateAfter(Long accountId, LocalDateTime since,
                        Pageable pageable);

        // Transactions récentes d'un compte (pour le profil client)
        @EntityGraph(attributePaths = "account")
        List<Transaction> findTop10ByAccountIdOrderByTransactionDateDesc(Long accountId);

        List<Transaction> findByAccountIdOrderByTransactionDateAsc(Long accountId);

        // Recherche dynamique améliorée
        @Query(value = "SELECT t FROM Transaction t WHERE " +
                        "(:status IS NULL OR t.status = :status) AND " +
                        "(:channel IS NULL OR t.channel = :channel) AND " +
                        "(:start IS NULL OR t.transactionDate >= :start) AND " +
                        "(:end IS NULL OR t.transactionDate <= :end)", countQuery = "SELECT COUNT(t) FROM Transaction t WHERE "
                                        +
                                        "(:status IS NULL OR t.status = :status) AND " +
                                        "(:channel IS NULL OR t.channel = :channel) AND " +
                                        "(:start IS NULL OR t.transactionDate >= :start) AND " +
                                        "(:end IS NULL OR t.transactionDate <= :end)")
        Page<Transaction> search(@Param("status") TransactionStatus status,
                        @Param("channel") TransactionChannel channel,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end,
                        Pageable pageable);

        @Query("SELECT DISTINCT t.status FROM Transaction t")
        List<TransactionStatus> findDistinctStatuses();

        @Query("SELECT DISTINCT t.channel FROM Transaction t")
        List<TransactionChannel> findDistinctChannels();

        // Statistiques globales
        @Query("SELECT COUNT(t) FROM Transaction t")
        long countAll();

        @Query("SELECT COUNT(t) FROM Transaction t WHERE t.isFraud = true")
        long countFrauds();

        @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.isFraud = true")
        java.math.BigDecimal totalFraudAmount();

        // Statistiques temporelles
        @Query(value = "SELECT DATE_TRUNC('hour', transaction_date) as hour, " +
                        "COUNT(*) as total, " +
                        "SUM(CASE WHEN is_fraud = true THEN 1 ELSE 0 END) as frauds " +
                        "FROM transaction " +
                        "WHERE transaction_date BETWEEN :start AND :end " +
                        "GROUP BY DATE_TRUNC('hour', transaction_date) " +
                        "ORDER BY hour", nativeQuery = true)
        List<Object[]> getTimeSeriesStats(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

        @Query("SELECT MAX(t.transactionDate) FROM Transaction t")
        Optional<LocalDateTime> findMaxTransactionDate();

        // Statistiques pour le dashboard "Transactions Analysées" (KPIs)
        long countByFraudScoreIsNotNull();

        long countByFraudScoreGreaterThan(Double threshold);

        @Query("SELECT AVG(t.fraudScore) FROM Transaction t WHERE t.fraudScore IS NOT NULL")
        Optional<Double> getAverageFraudScore();

        @Query("SELECT t.channel, COUNT(t) FROM Transaction t WHERE t.fraudScore IS NOT NULL GROUP BY t.channel")
        List<Object[]> countByChannel();

        @Query(value = "SELECT " +
                        "COALESCE(SUM(CASE WHEN COALESCE(fa.risk_level, 'LOW') = 'LOW' THEN t.amount ELSE 0 END), 0), " +
                        "COALESCE(SUM(CASE WHEN fa.risk_level = 'MEDIUM' THEN t.amount ELSE 0 END), 0), " +
                        "COALESCE(SUM(CASE WHEN fa.risk_level = 'HIGH' THEN t.amount ELSE 0 END), 0), " +
                        "COALESCE(SUM(CASE WHEN fa.risk_level = 'CRITICAL' THEN t.amount ELSE 0 END), 0) " +
                        "FROM transaction t " +
                        "LEFT JOIN fraud_alert fa ON t.id = fa.transaction_id " +
                        "WHERE t.fraud_score IS NOT NULL", nativeQuery = true)
        List<Object[]> sumAmountByRiskScore();
        
        @Query(value = "SELECT " +
                        "COUNT(CASE WHEN COALESCE(fa.risk_level, 'LOW') = 'LOW' THEN 1 END), " +
                        "COUNT(CASE WHEN fa.risk_level = 'MEDIUM' THEN 1 END), " +
                        "COUNT(CASE WHEN fa.risk_level = 'HIGH' THEN 1 END), " +
                        "COUNT(CASE WHEN fa.risk_level = 'CRITICAL' THEN 1 END) " +
                        "FROM transaction t " +
                        "LEFT JOIN fraud_alert fa ON t.id = fa.transaction_id " +
                        "WHERE t.fraud_score IS NOT NULL", nativeQuery = true)
        List<Object[]> countByRiskLevel();

        // Recherche par UUID
        @Query("SELECT t FROM Transaction t WHERE t.transactionUuid = :uuid")
        Optional<Transaction> findByTransactionUuid(@Param("uuid") UUID uuid);

        @Query("SELECT t FROM Transaction t WHERE t.transactionUuid IN :uuids")
        List<Transaction> findAllByTransactionUuidIn(@Param("uuids") List<UUID> uuids);

        // Méthode de calcul de moyenne glissante pour le drift de distribution
        @Query(value = "SELECT AVG(fraud_score) FROM (" +
                        "SELECT fraud_score FROM transaction " +
                        "WHERE model_id = :modelId AND fraud_score IS NOT NULL " +
                        "ORDER BY created_at DESC LIMIT :limit" +
                        ") as sub", nativeQuery = true)
        Optional<Double> findRecentAverageFraudScoreByModelId(@Param("modelId") Long modelId,
                        @Param("limit") int limit);

        // Compte le nombre de transactions traitées par un modèle spécifique sur une
        // période
        long countByModelAndCreatedAtAfter(com.fraud.backend.data.entity.frauddetection.ModelML model,
                        LocalDateTime since);

        @Modifying
        @Transactional
        @Query("UPDATE Transaction t SET t.isFraud = false, t.fraudScore = null, t.fraudReason = null, t.model = null")
        void resetAllScoringData();
}