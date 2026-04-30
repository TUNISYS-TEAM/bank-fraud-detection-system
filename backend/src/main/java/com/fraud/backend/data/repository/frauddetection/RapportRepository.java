package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.Rapport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RapportRepository extends JpaRepository<Rapport, Long> {

    Optional<Rapport> findByRapportUuid(UUID rapportUuid);

    // Rapports générés par un utilisateur (triés par date DESC)
    Page<Rapport> findByGeneratedBy_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Rapports par type
    Page<Rapport> findByType(String type, Pageable pageable);

    // Rapports par status
    Page<Rapport> findByStatus(String status, Pageable pageable);

    // Rapports générés par un utilisateur avec un type spécifique
    Page<Rapport> findByGeneratedBy_IdAndTypeOrderByCreatedAtDesc(Long userId, String type, Pageable pageable);

    @Query(value = "SELECT * FROM rapport r WHERE r.distribution_list @> ('[{\"userId\": ' || :userId || '}]')::jsonb ORDER BY r.created_at DESC", 
           countQuery = "SELECT count(*) FROM rapport r WHERE r.distribution_list @> ('[{\"userId\": ' || :userId || '}]')::jsonb",
           nativeQuery = true)
    Page<Rapport> findReceivedRapports(@Param("userId") Long userId, Pageable pageable);

    @Query(value = "SELECT count(*) FROM rapport r WHERE r.distribution_list @> ('[{\"userId\": ' || :userId || ', \"isRead\": false}]')::jsonb", nativeQuery = true)
    long countUnreadRapports(@Param("userId") Long userId);
}