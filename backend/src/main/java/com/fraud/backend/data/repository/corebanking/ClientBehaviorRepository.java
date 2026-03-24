package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClientBehaviorRepository extends JpaRepository<ClientBehavior, Long> {
    
    @EntityGraph(attributePaths = "account")
    Optional<ClientBehavior> findByAccountId(Long accountId);
    
    @EntityGraph(attributePaths = "account")
    Optional<ClientBehavior> findByAccount_AccountNumber(String accountNumber);
}