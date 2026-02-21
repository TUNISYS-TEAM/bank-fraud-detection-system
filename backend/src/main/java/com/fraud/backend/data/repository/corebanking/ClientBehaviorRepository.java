package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface ClientBehaviorRepository extends JpaRepository<ClientBehavior, Long> {
    
    Optional<ClientBehavior> findByClientId(Long clientId);
    
    Optional<ClientBehavior> findByAccountId(Long accountId);
    
    Optional<ClientBehavior> findByClientIdAndAccountId(Long clientId, Long accountId);
    
    List<ClientBehavior> findByClientRiskScoreGreaterThan(Double threshold);
    
    @Query("SELECT cb FROM ClientBehavior cb WHERE cb.clientRiskScore > 0.8 ORDER BY cb.clientRiskScore DESC")
    List<ClientBehavior> findCriticalRiskClients();
    
    @Query("SELECT cb FROM ClientBehavior cb WHERE cb.clientRiskScore BETWEEN 0.6 AND 0.8 ORDER BY cb.clientRiskScore DESC")
    List<ClientBehavior> findHighRiskClients();
    
    List<ClientBehavior> findByClientIsUnusualRegionTrue();
    
    List<ClientBehavior> findByClientIsUnusualChannelTrue();
    
    List<ClientBehavior> findByClientIsUnusualCategoryTrue();
    
    @Query("SELECT cb FROM ClientBehavior cb WHERE cb.clientIsUnusualRegion = true OR cb.clientIsUnusualChannel = true OR cb.clientIsUnusualCategory = true")
    List<ClientBehavior> findClientsWithAnyUnusualBehavior();
    
    List<ClientBehavior> findByClientTotalTransactionsGreaterThan(Integer minTransactions);
    
    List<ClientBehavior> findByClientTotalTransactionsLessThan(Integer maxTransactions);
    
    List<ClientBehavior> findByClientAvgTxAmountGreaterThan(Double amount);
    
    List<ClientBehavior> findByClientAvgTxAmountLessThan(Double amount);
    
    @Query("SELECT cb FROM ClientBehavior cb LEFT JOIN FETCH cb.client LEFT JOIN FETCH cb.account WHERE cb.id = :id")
    Optional<ClientBehavior> findByIdWithDetails(@Param("id") Long id);
    
    @Query("SELECT cb FROM ClientBehavior cb LEFT JOIN FETCH cb.client LEFT JOIN FETCH cb.account WHERE cb.clientRiskScore > 0.6 ORDER BY cb.clientRiskScore DESC")
    List<ClientBehavior> findAllHighRiskWithDetails();
    
    @Query("SELECT CASE " +
           "WHEN cb.clientRiskScore > 0.8 THEN 'CRITICAL' " +
           "WHEN cb.clientRiskScore > 0.6 THEN 'HIGH' " +
           "WHEN cb.clientRiskScore > 0.3 THEN 'MEDIUM' " +
           "ELSE 'LOW' END as riskLevel, COUNT(cb) " +
           "FROM ClientBehavior cb GROUP BY riskLevel")
    List<Object[]> countByRiskLevel();
    
    List<ClientBehavior> findByLastCalculatedAtAfter(LocalDateTime date);
}