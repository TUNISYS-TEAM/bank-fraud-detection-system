package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.Client;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

    @Query("SELECT c FROM Client c LEFT JOIN FETCH c.accounts WHERE c.clientId = :clientId")
    Optional<Client> findWithAccountsByClientId(@Param("clientId") Long clientId);

    @Query("""
            SELECT c FROM Client c
            WHERE (LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(CONCAT(c.lastName, ' ', c.firstName)) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.lastName) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:accountCount IS NULL OR SIZE(c.accounts) = :accountCount)
            """)
    Page<Client> searchClients(@Param("search") String search, @Param("accountCount") Integer accountCount, Pageable pageable);

    List<Client> findByLastNameContainingIgnoreCase(String lastName);

    List<Client> findByFirstNameContainingIgnoreCase(String firstName);
}