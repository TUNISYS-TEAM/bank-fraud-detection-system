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

    Page<Dataset> findByStatusAndType(DatasetStatus status, DatasetType type, Pageable pageable);

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

    @Query("SELECT d FROM Dataset d WHERE d.type = :type AND d.status IN :statuses AND " +
            "(LOWER(d.datasetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.datasetCode) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<Dataset> findTrainingDatasetsBySearchTerm(
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