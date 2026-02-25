package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.Rapport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RapportRepository extends JpaRepository<Rapport, Long> {

    Optional<Rapport> findByRapportUuid(UUID rapportUuid);

    // Rapports d’un modèle ML
    Page<Rapport> findByModel_Id(Long modelId, Pageable pageable);

    // Filtrage par type
    Page<Rapport> findByType(String type, Pageable pageable);

    // Filtrage par statut
    Page<Rapport> findByStatus(String status, Pageable pageable);

    // Rapports d’un modèle triés par date de génération décroissante
    Page<Rapport> findByModel_IdOrderByGeneratedAtDesc(
            Long modelId,
            Pageable pageable
    );
}