package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientBehaviorRepository extends JpaRepository<ClientBehavior, Long> {
    
    @EntityGraph(attributePaths = "account")
    Optional<ClientBehavior> findByAccountId(Long accountId);
    
    @EntityGraph(attributePaths = "account")
    Optional<ClientBehavior> findByAccount_AccountNumber(String accountNumber);
    
    @EntityGraph(attributePaths = "account")
    @Query("SELECT cb FROM ClientBehavior cb WHERE cb.account.id IN :accountIds")
    List<ClientBehavior> findByAccountIdIn(@Param("accountIds") List<Long> accountIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM ClientBehavior cb WHERE cb.account.id IN :accountIds")
    void deleteByAccountIdIn(@Param("accountIds") java.util.Collection<Long> accountIds);
}