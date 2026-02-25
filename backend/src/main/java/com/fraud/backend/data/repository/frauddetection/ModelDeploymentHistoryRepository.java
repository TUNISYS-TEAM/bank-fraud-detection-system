package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.ModelDeploymentHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModelDeploymentHistoryRepository extends JpaRepository<ModelDeploymentHistory, Long> {

    /**
     * Historique d’un modèle
     */
    Page<ModelDeploymentHistory> findByModelIdOrderByDeploymentDateDesc(
            Long modelId,
            Pageable pageable
    );

    /**
     * Déploiements par environnement (DEV, TEST, PROD)
     */
    Page<ModelDeploymentHistory> findByEnvironment(
            String environment,
            Pageable pageable
    );

    /**
     * Filtrage par statut
     */
    Page<ModelDeploymentHistory> findByStatus(
            String status,
            Pageable pageable
    );
}