package com.fraud.backend.data.repository.frauddetection;

import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.DatasetStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DatasetRepository extends JpaRepository<Dataset, Long> {

    Optional<Dataset> findByDatasetUuid(UUID datasetUuid);

    Page<Dataset> findByStatus(DatasetStatus status, Pageable pageable);

    @Query("SELECT d FROM Dataset d WHERE " +
            "LOWER(d.datasetName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.datasetCode) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<Dataset> findByDatasetNameOrCode(@Param("searchTerm") String searchTerm, Pageable pageable);
    
    @Query("SELECT COALESCE(SUM(d.fileSizeMb), 0) FROM Dataset d")
    java.math.BigDecimal sumFileSizeMb();

    Page<Dataset> findByImportedAtAfter(LocalDateTime date, Pageable pageable);
}