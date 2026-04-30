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
                        Pageable pageable);

        // Modèles créés après une date
        Page<ModelML> findByCreatedAtAfter(
                        LocalDateTime date,
                        Pageable pageable);

        // Modèles validés par un user
        Page<ModelML> findByValidatedById(
                        Long userId,
                        Pageable pageable);

        // Top modèles par F1 score
        Page<ModelML> findAllByOrderByF1ScoreDesc(Pageable pageable);

        List<ModelML> findAllByOrderByCreatedAtDesc();

        Page<ModelML> findAllByOrderByCreatedAtDesc(Pageable pageable);

        // ==================== NEW METHODS FOR CREATOR FILTERING ====================

        /**
         * Find all models created by a specific user
         */
        List<ModelML> findByCreatedByIdOrderByCreatedAtDesc(Long createdById);

        /**
         * Find models created by a specific user with pagination
         */
        Page<ModelML> findByCreatedByIdOrderByCreatedAtDesc(Long createdById, Pageable pageable);

        /**
         * Count models created by a specific user
         */
        long countByCreatedById(Long createdById);

        /**
         * Find models by creator ID and status
         */
        List<ModelML> findByCreatedByIdAndStatusOrderByCreatedAtDesc(Long createdById, String status);

        /**
         * Find models by creator ID and status with pagination
         */
        Page<ModelML> findByCreatedByIdAndStatusOrderByCreatedAtDesc(Long createdById, String status,
                        Pageable pageable);

        Page<ModelML> findByValidatedByIdOrderByCreatedAtDesc(Long validatedById, Pageable pageable);

        List<ModelML> findByCreatedByIdAndStatusInOrderByCreatedAtDesc(Long createdById, List<String> statuses);

        @org.springframework.data.jpa.repository.Query("SELECT m FROM ModelML m WHERE (m.createdBy.id = :managerId OR m.createdBy.manager.id = :managerId) AND m.status = :status ORDER BY m.createdAt DESC")
        List<ModelML> findByTeamAndStatus(@org.springframework.data.repository.query.Param("managerId") Long managerId, @org.springframework.data.repository.query.Param("status") String status);

        @org.springframework.data.jpa.repository.Query("SELECT m FROM ModelML m WHERE (m.createdBy.id = :managerId OR m.createdBy.manager.id = :managerId) ORDER BY m.createdAt DESC")
        List<ModelML> findByTeam(@org.springframework.data.repository.query.Param("managerId") Long managerId);
}