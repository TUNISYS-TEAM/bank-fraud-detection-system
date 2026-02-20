package com.fraud.backend.data.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

@Entity
@Table(name = "model_ml")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelML {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "model_uuid", unique = true)
    private UUID modelUuid;
    
    @Column(name = "model_name")
    private String modelName;
    
    @Column(name = "model_type")
    private String modelType;
    
    private String version;
    
    private Double auc;
    private Double precision;
    private Double recall;
    
    @Column(name = "f1_score")
    private Double f1Score;
    
    private String status;
    
    @ManyToOne
    @JoinColumn(name = "created_by_id")
    private AppUser createdBy;
    
    @ManyToOne
    @JoinColumn(name = "validated_by_id")
    private AppUser validatedBy;
    
    @Column(name = "model_path")
    private String modelPath;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_importance", columnDefinition = "jsonb")
    private Map<String, Object> featureImportance;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> hyperparameters;
    
    @Column(name = "training_date")
    private LocalDateTime trainingDate;
    
    @Column(name = "validation_date")
    private LocalDateTime validationDate;
    
    @Column(name = "deployment_date")
    private LocalDateTime deploymentDate;
    
    private String description;
    private String notes;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}