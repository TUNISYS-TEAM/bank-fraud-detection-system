package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    
    Optional<Account> findByAccountNumber(String accountNumber);
    
    List<Account> findByClientId(Long clientId);
    
    Page<Account> findByClientId(Long clientId, Pageable pageable);
    
    List<Account> findByClientIdAndIsActiveTrue(Long clientId);
    
    List<Account> findByRiskLevel(String riskLevel);
    
    List<Account> findByAccountType(String accountType);
    
    List<Account> findByCurrency(String currency);
    
    List<Account> findByBalanceGreaterThan(BigDecimal balance);
    
    List<Account> findByBalanceLessThan(BigDecimal balance);
    
    List<Account> findByBalanceBetween(BigDecimal min, BigDecimal max);
    
    @Query("SELECT a FROM Account a WHERE a.isActive = true AND a.riskLevel IN ('HIGH', 'CRITICAL')")
    List<Account> findHighRiskActiveAccounts();
    
    List<Account> findByClientIdAndRiskLevel(Long clientId, String riskLevel);
    
    @Query("SELECT a FROM Account a LEFT JOIN FETCH a.transactions WHERE a.id = :id")
    Optional<Account> findByIdWithTransactions(@Param("id") Long id);
    
    @Query("SELECT a FROM Account a LEFT JOIN FETCH a.client LEFT JOIN FETCH a.clientBehavior WHERE a.id = :id")
    Optional<Account> findByIdWithDetails(@Param("id") Long id);
    
    @Query("SELECT COUNT(a) FROM Account a WHERE a.client.id = :clientId")
    long countByClientId(@Param("clientId") Long clientId);
    
    @Query("SELECT SUM(a.balance) FROM Account a WHERE a.client.id = :clientId AND a.isActive = true")
    BigDecimal getTotalBalanceForClient(@Param("clientId") Long clientId);
    
    List<Account> findByOpenedDateBetween(LocalDateTime start, LocalDateTime end);
    
    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.balance >= :minBalance")
    List<Account> findByTypeAndMinBalance(@Param("type") String type, @Param("minBalance") BigDecimal minBalance);
}