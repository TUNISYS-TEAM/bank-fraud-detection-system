package com.fraud.backend.data.entity.frauddetection;

import com.fraud.backend.data.enums.DatasetStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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
    @Column(name = "status", nullable = false, length = 20)
    private DatasetStatus status;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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
        if (status == null) {
            status = DatasetStatus.INACTIVE;
        }
    }

    // =========================
    // Business Methods
    // =========================

    @Transient
    public boolean isActive() {
        return DatasetStatus.ACTIVE.equals(this.status);
    }

    @Transient
    public boolean isInactive() {
        return DatasetStatus.INACTIVE.equals(this.status);
    }

    public void activate() {
        this.status = DatasetStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = DatasetStatus.INACTIVE;
    }

    public void updateFileStats(String filePath, BigDecimal fileSizeMb) {
        this.filePath = filePath;
        this.fileSizeMb = fileSizeMb;
        this.importedAt = LocalDateTime.now();
    }
}