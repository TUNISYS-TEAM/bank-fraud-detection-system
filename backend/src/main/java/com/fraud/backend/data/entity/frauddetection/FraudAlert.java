package com.fraud.backend.data.entity.frauddetection;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.corebanking.Account;
import com.fraud.backend.data.entity.corebanking.Transaction;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

@Entity
@Table(name = "fraud_alert")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudAlert {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "alert_uuid", unique = true)
    private UUID alertUuid;
    
    @ManyToOne
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;
    
    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;
    
    @ManyToOne
    @JoinColumn(name = "user_id")
    private AppUser user;
    
    @Column(name = "fraud_score")
    private Double fraudScore;
    
    @Column(name = "supervised_score")
    private Double supervisedScore;
    
    @Column(name = "anomaly_score")
    private Double anomalyScore;
    
    @ManyToOne
    @JoinColumn(name = "model_used_id")
    private ModelML modelUsed;
    
    @Column(name = "risk_level")
    private String riskLevel;
    
    @Column(name = "alert_type")
    private String alertType;
    
    private String status;
    
    @ManyToOne
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;
    
    @Column(name = "review_date")
    private LocalDateTime reviewDate;
    
    @Column(name = "review_comment")
    private String reviewComment;
    
    @Column(name = "action_taken")
    private String actionTaken;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> featuresSnapshot;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}