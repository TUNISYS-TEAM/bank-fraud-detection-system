package com.fraud.backend.data.repository.corebanking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.fraud.backend.data.entity.corebanking.Transaction;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionUuid(UUID transactionUuid);

    Page<Transaction> findByAccountId(Long accountId, Pageable pageable);

    Page<Transaction> findByIsFraudTrue(Pageable pageable);

    Page<Transaction> findByStatus(String status, Pageable pageable);

    Page<Transaction> findByChannel(String channel, Pageable pageable);

    Page<Transaction> findByFraudScoreGreaterThan(Double threshold, Pageable pageable);

    /**
     * Transactions sur période (dashboard / reporting)
     */
    Page<Transaction> findByTransactionDateBetween(
            LocalDateTime start,
            LocalDateTime end,
            Pageable pageable
    );

    /**
     * Transactions récentes d'un compte (pour le profil client)
     */
    Page<Transaction> findByAccountIdAndTransactionDateAfter(
            Long accountId,
            LocalDateTime since,
            Pageable pageable
    );
}