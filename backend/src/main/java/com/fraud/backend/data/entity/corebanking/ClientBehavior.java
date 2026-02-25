package com.fraud.backend.data.entity.corebanking;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "client_behavior",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = { "account_id" })
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientBehavior {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_avg_tx_amount", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal clientAvgTxAmount = BigDecimal.ZERO;

    @Column(name = "client_avg_tx_per_day", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal clientAvgTxPerDay = BigDecimal.ZERO;

    @Column(name = "client_total_transactions")
    @Builder.Default
    private Integer clientTotalTransactions = 0;

    @Column(name = "client_total_days_since_first")
    @Builder.Default
    private Integer clientTotalDaysSinceFirst = 0;

    @Column(name = "client_amount_deviation", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal clientAmountDeviation = BigDecimal.ZERO;

    @Column(name = "client_abs_amount_deviation", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal clientAbsAmountDeviation = BigDecimal.ZERO;

    @Column(name = "client_amount_z_score", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal clientAmountZScore = BigDecimal.ZERO;

    @Column(name = "client_amount_max_ratio", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal clientAmountMaxRatio = BigDecimal.ONE;

    @Column(name = "client_is_new_max")
    @Builder.Default
    private Boolean clientIsNewMax = false;

    @Column(name = "client_is_new_min")
    @Builder.Default
    private Boolean clientIsNewMin = false;

    @Column(name = "client_time_since_last_tx")
    @Builder.Default
    private Long clientTimeSinceLastTx = 0L;

    @Column(name = "client_hours_since_last_tx", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal clientHoursSinceLastTx = BigDecimal.ZERO;

    @Column(name = "client_usual_region")
    private String clientUsualRegion;

    @Column(name = "client_usual_channel")
    private String clientUsualChannel;

    @Column(name = "client_usual_category")
    private String clientUsualCategory;

    @Column(name = "client_region_freq", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal clientRegionFreq = BigDecimal.ONE;

    @Column(name = "client_channel_freq", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal clientChannelFreq = BigDecimal.ONE;

    @Column(name = "client_category_freq", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal clientCategoryFreq = BigDecimal.ONE;

    @Column(name = "client_is_unusual_region")
    @Builder.Default
    private Boolean clientIsUnusualRegion = false;

    @Column(name = "client_is_unusual_channel")
    @Builder.Default
    private Boolean clientIsUnusualChannel = false;

    @Column(name = "client_is_unusual_category")
    @Builder.Default
    private Boolean clientIsUnusualCategory = false;

    @Column(name = "client_risk_score", precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal clientRiskScore = BigDecimal.ZERO;

    @Column(name = "last_calculated_at")
    private LocalDateTime lastCalculatedAt;

    // Relation unique désormais via Account uniquement
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}