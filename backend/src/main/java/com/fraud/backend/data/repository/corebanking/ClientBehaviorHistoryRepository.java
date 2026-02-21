package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.ClientBehaviorHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClientBehaviorHistoryRepository extends JpaRepository<ClientBehaviorHistory, Long> {
    
    List<ClientBehaviorHistory> findByClientId(Long clientId);
    
    List<ClientBehaviorHistory> findByAccountId(Long accountId);
    
    List<ClientBehaviorHistory> findByTransactionId(Long transactionId);
    
    List<ClientBehaviorHistory> findByClientIdAndCalculationDateBetween(Long clientId, LocalDateTime start, LocalDateTime end);
    
    List<ClientBehaviorHistory> findByAccountIdAndCalculationDateBetween(Long accountId, LocalDateTime start, LocalDateTime end);
    
    @Query("SELECT h FROM ClientBehaviorHistory h WHERE h.client.id = :clientId ORDER BY h.calculationDate DESC")
    List<ClientBehaviorHistory> findLatestByClientId(@Param("clientId") Long clientId);
    
    List<ClientBehaviorHistory> findByCalculationDate(LocalDateTime date);
    
    List<ClientBehaviorHistory> findByCalculationDateBetween(LocalDateTime start, LocalDateTime end);
    
    @Query("SELECT h FROM ClientBehaviorHistory h LEFT JOIN FETCH h.client LEFT JOIN FETCH h.account LEFT JOIN FETCH h.transaction WHERE h.id = :id")
    Optional<ClientBehaviorHistory> findByIdWithDetails(@Param("id") Long id);
    
    long countByClientId(Long clientId);
    
    void deleteByCalculationDateBefore(LocalDateTime date);
} 
    

