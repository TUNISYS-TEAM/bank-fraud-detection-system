package com.fraud.backend.data.entity.frauddetection;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Entity
@Table(name = "rapport")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rapport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rapport_uuid", unique = true)
    private UUID rapportUuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_id")
    private AppUser generatedBy;

    private String title;
    private String type;
    private String format;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_summary", columnDefinition = "jsonb")
    private Map<String, Object> dataSummary;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "total_transactions")
    private Integer totalTransactions;

    @Column(name = "fraud_detected")
    private Integer fraudDetected;

    @Column(name = "avg_fraud_score")
    private Double avgFraudScore;

    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "distribution_list", columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> distributionList = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (rapportUuid == null) {
            rapportUuid = UUID.randomUUID();
        }
        if (status == null) {
            status = "GENERATING";
        }
    }
}