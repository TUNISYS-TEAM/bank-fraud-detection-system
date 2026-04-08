package com.fraud.backend.data.entity.corebanking;

import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.enums.transactions.TransactionChannel;
import com.fraud.backend.data.enums.transactions.TransactionStatus;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = { "account", "model" })
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_uuid", unique = true)
    private UUID transactionUuid;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    private String currency = "TND";

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @Column(name = "merchant_category")
    private String merchantCategory;

    @Enumerated(EnumType.STRING)
    private TransactionChannel channel;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "postal_code_merchant")
    private String postalCodeMerchant;

    @Column(name = "postal_code_client")
    private String postalCodeClient;

    @Column(name = "is_fraud")
    @Builder.Default
    private Boolean isFraud = false;

    @Column(name = "fraud_score", precision = 5, scale = 4)
    private BigDecimal fraudScore;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode fraudReason;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "device_id")
    private String deviceId;

    // Relations
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    private ModelML model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dataset_id")
    private Dataset dataset;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}