package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public interface DatasetRepository extends JpaRepository<Dataset, Long> {

    Optional<Dataset> findByDatasetUuid(UUID datasetUuid);

    Page<Dataset> findByStatus(DatasetStatus status, Pageable pageable);

    Page<Dataset> findByType(DatasetType type, Pageable pageable);

    Page<Dataset> findByVisibility(Boolean visibility, Pageable pageable);

    Page<Dataset> findByStatusAndType(DatasetStatus status, DatasetType type, Pageable pageable);

    Page<Dataset> findByVisibilityAndType(Boolean visibility, DatasetType type, Pageable pageable);

    @Query("SELECT d FROM Dataset d WHERE " +
            "LOWER(d.datasetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.datasetCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<Dataset> findByDatasetNameOrCode(@Param("searchTerm") String searchTerm, Pageable pageable);

    @Query("SELECT COUNT(d) FROM Dataset d WHERE d.type IN :types")
    long countByTypeIn(@Param("types") java.util.Collection<DatasetType> types);

    @Query("SELECT COALESCE(SUM(d.fileSizeMb), 0) FROM Dataset d")
    java.math.BigDecimal sumFileSizeMb();

    @Query("SELECT COALESCE(SUM(d.fileSizeMb), 0) FROM Dataset d WHERE d.type IN :types")
    java.math.BigDecimal sumFileSizeMbByTypeIn(@Param("types") java.util.Collection<DatasetType> types);

    Page<Dataset> findByImportedAtAfter(LocalDateTime date, Pageable pageable);

    List<Dataset> findByTypeAndStatusIn(DatasetType type, List<DatasetStatus> statuses);

    Page<Dataset> findByTypeAndStatusIn(DatasetType type, List<DatasetStatus> statuses, Pageable pageable);

    List<Dataset> findByTypeAndVisibility(DatasetType type, Boolean visibility);

    Page<Dataset> findByTypeAndVisibility(DatasetType type, Boolean visibility, Pageable pageable);

    List<Dataset> findByTypeAndDatasetNameContaining(DatasetType type, String name);

    // ==================== SINGLE VISIBLE DATASET QUERIES ====================

    /**
     * Find the single visible dataset of a given type
     */
    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.visibility = true")
    Optional<Dataset> findVisibleByType(@Param("type") DatasetType type);

    /**
     * Find visible dataset of a given type excluding a specific UUID
     * Used when activating a dataset to check if another is already visible
     */
    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.visibility = true AND d.datasetUuid != :excludeUuid")
    Optional<Dataset> findVisibleByTypeExcluding(@Param("type") DatasetType type, @Param("excludeUuid") UUID excludeUuid);

    /**
     * Get all visible datasets of a given type (should be 0 or 1, but method returns List for flexibility)
     */
    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.visibility = true")
    List<Dataset> findAllVisibleByType(@Param("type") DatasetType type);

    // ==================== TRAINING DATASETS WITH VISIBILITY ====================

    /**
     * Find training datasets (TRANSACTIONS_TRAIN_ENRICHED) with specific statuses
     * and visibility = true with search term on name or code
     */
    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.status IN :statuses AND d.visibility = true AND " +
            "(LOWER(d.datasetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.datasetCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<Dataset> findTrainingDatasetsBySearchTerm(
            @Param("type") DatasetType type,
            @Param("statuses") List<DatasetStatus> statuses,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);

    /**
     * Find training datasets (TRANSACTIONS_TRAIN_ENRICHED) with specific statuses and visibility = true
     */
    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.status IN :statuses AND d.visibility = true")
    Page<Dataset> findByTypeAndStatusInAndVisibilityTrue(
            @Param("type") DatasetType type,
            @Param("statuses") List<DatasetStatus> statuses,
            Pageable pageable);

    /**
     * Find all TRANSACTIONS_TRAIN_ENRICHED datasets with visibility = true and search term
     */
    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.visibility = true AND " +
            "(LOWER(d.datasetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.datasetCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<Dataset> findByTypeAndSearchTermAndVisibilityTrue(
            @Param("type") DatasetType type,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);

    /**
     * Find all TRANSACTIONS_TRAIN_ENRICHED datasets with visibility = true
     */
    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.visibility = true")
    Page<Dataset> findByTypeAndVisibilityTrue(
            @Param("type") DatasetType type,
            Pageable pageable);

    // ==================== ENRICHED DATASET QUERIES ====================

    /**
     * Find enriched dataset by source dataset ID in metadata (using native query for JSONB)
     */
    @Query(value = "SELECT * FROM dataset WHERE type = :type AND feature_columns->'enrichment'->>'sourceDatasetId' = :sourceId", 
           nativeQuery = true)
    List<Dataset> findEnrichedBySourceIdNative(
            @Param("type") String type, 
            @Param("sourceId") String sourceId);

    // ==================== ORIGINAL METHODS (kept for backward compatibility) ====================

    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.status IN :statuses AND " +
            "(LOWER(d.datasetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.datasetCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<Dataset> findTrainingDatasetsBySearchTermOriginal(
            @Param("type") DatasetType type,
            @Param("statuses") List<DatasetStatus> statuses,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);

    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND " +
            "(LOWER(d.datasetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.datasetCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<Dataset> findByTypeAndSearchTerm(
            @Param("type") DatasetType type,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);
}