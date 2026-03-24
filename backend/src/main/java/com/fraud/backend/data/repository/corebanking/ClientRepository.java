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

    List<Client> findByFirstNameContainingIgnoreCase(String firstName);
    
    @Query(value = "SELECT account_count, COUNT(*) FROM (" +
           "  SELECT c.client_id, COUNT(a.id) as account_count " +
           "  FROM client c " +
           "  LEFT JOIN account a ON c.client_id = a.client_id " +
           "  GROUP BY c.client_id" +
           ") sub " +
           "GROUP BY account_count", nativeQuery = true)
    List<Object[]> getRawAccountCounts();

    @Query("SELECT " +
           "CASE WHEN (YEAR(CURRENT_DATE) - YEAR(c.dateOfBirth)) < 30 THEN 'Moins de 30 ans' " +
           "     WHEN (YEAR(CURRENT_DATE) - YEAR(c.dateOfBirth)) <= 45 THEN '30 – 45 ans' " +
           "     WHEN (YEAR(CURRENT_DATE) - YEAR(c.dateOfBirth)) <= 60 THEN '46 – 60 ans' " +
           "     ELSE 'Plus de 60 ans' END, " +
           "COUNT(c) " +
           "FROM Client c " +
           "GROUP BY CASE WHEN (YEAR(CURRENT_DATE) - YEAR(c.dateOfBirth)) < 30 THEN 'Moins de 30 ans' " +
           "              WHEN (YEAR(CURRENT_DATE) - YEAR(c.dateOfBirth)) <= 45 THEN '30 – 45 ans' " +
           "              WHEN (YEAR(CURRENT_DATE) - YEAR(c.dateOfBirth)) <= 60 THEN '46 – 60 ans' " +
           "              ELSE 'Plus de 60 ans' END")
    List<Object[]> getAgeDistribution();
}