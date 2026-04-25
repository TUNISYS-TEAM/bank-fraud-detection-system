package com.fraud.backend.data.entity.frauddetection;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;
import com.fraud.backend.data.entity.accessmanagement.AppUser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

@Entity
@Table(name = "dataset")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_uuid", unique = true, nullable = false, updatable = false)
    private UUID datasetUuid;

    @Column(name = "dataset_code", unique = true, nullable = false, length = 50)
    private String datasetCode;

    @Column(name = "dataset_name", nullable = false, length = 255)
    private String datasetName;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "file_size_mb", precision = 10, scale = 2)
    private BigDecimal fileSizeMb;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private DatasetStatus status;

    @Column(name = "visibility", nullable = false)
    @Builder.Default
    private Boolean visibility = false;

    @Column(name = "qualified", nullable = false)
    @Builder.Default
    private Boolean qualified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "dataset_type", nullable = false, length = 30)
    private DatasetType type;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @Column(name = "row_count")
    private Long rowCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_columns", columnDefinition = "jsonb")
    private Map<String, Object> featureColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "statistics", columnDefinition = "jsonb")
    private Map<String, Object> statistics;

    // ==================== TEAM ASSIGNMENT ====================
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_manager_id")
    private AppUser assignedManager;

    // =========================
    // Lifecycle Hooks
    // =========================

    @PrePersist
    public void prePersist() {
        if (datasetUuid == null) {
            datasetUuid = UUID.randomUUID();
        }
        if (importedAt == null) {
            importedAt = LocalDateTime.now();
        }
        if (visibility == null) {
            visibility = false;
        }
        if (type == null) {
            type = DatasetType.TRANSACTIONS_TRAIN;
        }
        if (qualified == null) {
            qualified = false;
        }
        // status can be null now
    }

    // =========================
    // Business Methods
    // =========================

    @Transient
    public boolean isVisible() {
        return Boolean.TRUE.equals(this.visibility);
    }

    public void setVisible(boolean visible) {
        this.visibility = visible;
    }

    public void markAsProcessed() {
        this.status = DatasetStatus.PROCESSED;
    }

    public void markAsCleaned() {
        this.status = DatasetStatus.CLEANED;
    }

    public void markAsInactive() {
        this.status = null;
        this.visibility = false;
    }

    public void markAsQualified() {
        this.qualified = true;
    }

    public void updateFileStats(String filePath, BigDecimal fileSizeMb, Long rowCount) {
        this.filePath = filePath;
        this.fileSizeMb = fileSizeMb;
        this.rowCount = rowCount;
        this.importedAt = LocalDateTime.now();
    }
}