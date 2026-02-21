package com.fraud.backend.data.entity.corebanking;

import com.fraud.backend.data.entity.frauddetection.ModelML;  

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "transaction")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "transaction_uuid", unique = true)
    private UUID transactionUuid;
    
    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;
    
    private BigDecimal amount;
    private String currency;
    
    @Column(name = "transaction_date")
    private LocalDateTime transactionDate;
    
    private String description;
    
    @Column(name = "merchant_name")
    private String merchantName;
    
    @Column(name = "merchant_category")
    private String merchantCategory;
    
    private String channel;
    private String status;
    
    @Column(name = "postal_code_merchant")
    private String postalCodeMerchant;
    
    @Column(name = "postal_code_client")
    private String postalCodeClient;
    
    @Column(name = "is_fraud")
    private Boolean isFraud;
    
    @Column(name = "fraud_score")
    private Double fraudScore;
    
    @Column(name = "fraud_reason")
    private String fraudReason;
    
    @ManyToOne
    @JoinColumn(name = "model_used_id")
    private ModelML modelUsed;
    
    @Column(name = "ip_address")
    private String ipAddress;
    
    @Column(name = "device_id")
    private String deviceId;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}