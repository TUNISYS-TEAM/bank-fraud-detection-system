package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {
    
    List<Client> findByLastName(String lastName);
    
    List<Client> findByFirstName(String firstName);
    
    List<Client> findByFirstNameAndLastName(String firstName, String lastName);
    
    @Query("SELECT c FROM Client c WHERE LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(c.lastName) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<Client> searchByName(@Param("search") String search);
    
    List<Client> findByDateOfBirthBetween(LocalDate startDate, LocalDate endDate);
    
    List<Client> findByDateOfBirthAfter(LocalDate date);
    
    List<Client> findByDateOfBirthBefore(LocalDate date);
    
    @Query("SELECT c, COUNT(a) as accountCount FROM Client c LEFT JOIN c.accounts a GROUP BY c ORDER BY accountCount DESC")
    List<Object[]> findClientsWithMostAccounts();
    
    List<Client> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    
    boolean existsByFirstNameAndLastName(String firstName, String lastName);
    
    @Query("SELECT c FROM Client c LEFT JOIN FETCH c.accounts WHERE c.id = :id")
    Optional<Client> findByIdWithAccounts(@Param("id") Long id);
    
    @Query("SELECT c FROM Client c LEFT JOIN FETCH c.clientBehavior WHERE c.id = :id")
    Optional<Client> findByIdWithBehavior(@Param("id") Long id);
}