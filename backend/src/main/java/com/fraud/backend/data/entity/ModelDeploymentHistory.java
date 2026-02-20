package com.fraud.backend.data.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "model_deployment_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelDeploymentHistory {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "model_id")
    private ModelML model;
    
    @ManyToOne
    @JoinColumn(name = "deployed_by_id")
    private AppUser deployedBy;
    
    @Column(name = "deployment_date")
    private LocalDateTime deploymentDate;
    
    @Column(name = "undeployment_date")
    private LocalDateTime undeploymentDate;
    
    private String environment;
    private String status;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "performance_at_deployment", columnDefinition = "jsonb")
    private Map<String, Object> performanceAtDeployment;
    
    private String notes;
}