package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.enums.transactions.TransactionChannel;
import com.fraud.backend.data.enums.transactions.TransactionStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

        @Override
        @EntityGraph(attributePaths = "account")
        Optional<Transaction> findById(Long id);

        @EntityGraph(attributePaths = "account")
        Optional<Transaction> findByTransactionUuid(UUID transactionUuid);

        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByAccountId(Long accountId, Pageable pageable);

        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByIsFraudTrue(Pageable pageable);

        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByFraudScoreGreaterThan(Double threshold, Pageable pageable);

        // Transactions récentes d'un compte (pour le profil client)
        @EntityGraph(attributePaths = "account")
        Page<Transaction> findByAccountIdAndTransactionDateAfter(
                        Long accountId,
                        LocalDateTime since,
                        Pageable pageable);

        // Filtrage combiné (status + channel + date range) — paramètres optionnels
        @EntityGraph(attributePaths = "account")
        @Query("""
                            SELECT t
                            FROM Transaction t
                            WHERE (:status IS NULL OR t.status = :status)
                              AND (:channel IS NULL OR t.channel = :channel)
                              AND (cast(:start as localdatetime) IS NULL OR t.transactionDate >= :start)
                              AND (cast(:end as localdatetime) IS NULL OR t.transactionDate <= :end)
                        """)
        Page<Transaction> search(
                        @Param("status") TransactionStatus status,
                        @Param("channel") TransactionChannel channel,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end,
                        Pageable pageable);

        // Recherche des statuts uniques
        @Query("""
                            SELECT DISTINCT t.status
                            FROM Transaction t
                            WHERE t.status IS NOT NULL
                            ORDER BY t.status
                        """)
        List<TransactionStatus> findDistinctStatuses();

        // Recherche des canaux uniques
        @Query("""
                            SELECT DISTINCT t.channel
                            FROM Transaction t
                            WHERE t.channel IS NOT NULL
                            ORDER BY t.channel
                        """)
        List<TransactionChannel> findDistinctChannels();

        // Get all transactions for an account in chronological order (for backfill)
        @EntityGraph(attributePaths = "account")
        List<Transaction> findByAccountIdOrderByTransactionDateAsc(Long accountId);

        @Query("SELECT MAX(t.transactionDate) FROM Transaction t")
        java.util.Optional<LocalDateTime> findMaxTransactionDate();

        @Query(value = "SELECT DATE_TRUNC('hour', transaction_date) as hour, " +
                        "COUNT(*) as total, " +
                        "SUM(CASE WHEN is_fraud = true THEN 1 ELSE 0 END) as frauds " +
                        "FROM transaction " +
                        "WHERE transaction_date BETWEEN :start AND :end " +
                        "GROUP BY DATE_TRUNC('hour', transaction_date) " +
                        "ORDER BY hour", nativeQuery = true)
        List<Object[]> getTimeSeriesStats(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

        @Query(value = "SELECT DATE_TRUNC('day', transaction_date) as day, " +
                        "COUNT(*) as total, " +
                        "SUM(CASE WHEN is_fraud = true THEN 1 ELSE 0 END) as frauds " +
                        "FROM transaction " +
                        "WHERE transaction_date BETWEEN :start AND :end " +
                        "GROUP BY DATE_TRUNC('day', transaction_date) " +
                        "ORDER BY day", nativeQuery = true)
        List<Object[]> getDailyTimeSeriesStats(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

        @Query("SELECT t FROM Transaction t WHERE t.transactionUuid IN :uuids")
        List<Transaction> findAllByTransactionUuidIn(@Param("uuids") List<UUID> uuids);
}