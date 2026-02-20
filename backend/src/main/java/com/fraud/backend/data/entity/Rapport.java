package com.fraud.backend.data.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
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
    
    @ManyToOne
    @JoinColumn(name = "user_id")
    private AppUser user;
    
    private String title;
    private String type;
    private String format;
    
    @Column(columnDefinition = "TEXT")
    private String content;
    
    @Column(name = "file_path")
    private String filePath;
    
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
    
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}