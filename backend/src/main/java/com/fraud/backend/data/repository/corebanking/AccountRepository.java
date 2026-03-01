package com.fraud.backend.data.repository.corebanking;

import org.springframework.data.jpa.repository.JpaRepository;
import com.fraud.backend.data.entity.corebanking.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @Query("SELECT a FROM Account a JOIN FETCH a.client WHERE a.id = :id")
    Optional<Account> findByIdWithClient(@Param("id") Long id);

    @Query("SELECT a FROM Account a JOIN FETCH a.client WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberWithClient(@Param("accountNumber") String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByClientId(Long clientId);

    Page<Account> findByRiskLevel(String riskLevel, Pageable pageable);

    Page<Account> findByIsActiveTrue(Pageable pageable);

    Page<Account> findByClientIdAndIsActiveTrue(Long clientId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(a.balance), 0) FROM Account a " +
            "WHERE a.client.id = :clientId AND a.isActive = true")
    BigDecimal sumActiveBalanceByClient(@Param("clientId") Long clientId);

    long countByClientId(Long clientId);

}