package com.fraud.backend.data.repository.corebanking;

import com.fraud.backend.data.entity.corebanking.Client;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

    List<Client> findByLastNameContainingIgnoreCase(String lastName);

    List<Client> findByFirstNameContainingIgnoreCase(String firstName);

    /**
     * Recherche globale sur nom + prénom
     */
    @Query("""
           SELECT c FROM Client c
           WHERE LOWER(c.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
              OR LOWER(c.lastName) LIKE LOWER(CONCAT('%', :search, '%'))
           """)
    Page<Client> searchByName(@Param("search") String search, Pageable pageable);

    @Query("SELECT c FROM Client c LEFT JOIN FETCH c.accounts WHERE c.id = :id")
    Optional<Client> findByIdWithAccounts(@Param("id") Long id);

    
}