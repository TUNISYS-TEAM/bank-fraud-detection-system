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

    @Query("SELECT a FROM Account a WHERE a.client.clientId = :clientId")
    List<Account> findByClientId(@Param("clientId") Long clientId);

    Page<Account> findByRiskLevel(String riskLevel, Pageable pageable);

    Page<Account> findByIsActiveTrue(Pageable pageable);

    @Query("SELECT a FROM Account a WHERE a.client.clientId = :clientId AND a.isActive = true")
    Page<Account> findByClientIdAndIsActiveTrue(@Param("clientId") Long clientId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(a.balance), 0) FROM Account a " +
            "WHERE a.client.clientId = :clientId AND a.isActive = true")
    BigDecimal sumActiveBalanceByClient(@Param("clientId") Long clientId);

    @Query("SELECT COUNT(a) FROM Account a WHERE a.client.clientId = :clientId")
    long countByClientId(@Param("clientId") Long clientId);

}