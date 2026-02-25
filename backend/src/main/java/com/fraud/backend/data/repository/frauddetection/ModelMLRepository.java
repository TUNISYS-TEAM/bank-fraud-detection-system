package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.ModelML;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Repository
public interface ModelMLRepository extends JpaRepository<ModelML, Long> {

    Optional<ModelML> findByModelUuid(UUID modelUuid);

    List<ModelML> findByStatus(String status);

    List<ModelML> findByModelType(String modelType);

    Optional<ModelML> findByModelNameAndVersion(String modelName, String version);

    // Status trié par date de création
    Page<ModelML> findByStatusOrderByCreatedAtDesc(
            String status,
            Pageable pageable
    );

    // Modèles créés après une date
    Page<ModelML> findByCreatedAtAfter(
            LocalDateTime date,
            Pageable pageable
    );

    // Modèles validés par un user
    Page<ModelML> findByValidatedById(
            Long userId,
            Pageable pageable
    );

    // Top modèles par F1 score
    Page<ModelML> findAllByOrderByF1ScoreDesc(Pageable pageable);
}