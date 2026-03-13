package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.Account;
import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClientBehaviorRepository extends JpaRepository<ClientBehavior, Long> {

    Optional<ClientBehavior> findByAccount(Account account);

    Optional<ClientBehavior> findByAccount_Id(Long accountId);
}