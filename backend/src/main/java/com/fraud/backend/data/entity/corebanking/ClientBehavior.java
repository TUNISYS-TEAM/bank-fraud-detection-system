package com.fraud.backend.data.entity.corebanking;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "client_behavior")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientBehavior {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @OneToOne
    @JoinColumn(name = "client_id")
    private Client client;
    
    @OneToOne
    @JoinColumn(name = "account_id")
    private Account account;
    
    @Column(name = "client_avg_tx_amount")
    private Double clientAvgTxAmount;
    
    @Column(name = "client_avg_tx_per_day")
    private Double clientAvgTxPerDay;
    
    @Column(name = "client_total_transactions")
    private Integer clientTotalTransactions;
    
    @Column(name = "client_total_days_since_first")
    private Integer clientTotalDaysSinceFirst;
    
    @Column(name = "client_amount_deviation")
    private Double clientAmountDeviation;
    
    @Column(name = "client_abs_amount_deviation")
    private Double clientAbsAmountDeviation;
    
    @Column(name = "client_amount_z_score")
    private Double clientAmountZScore;
    
    @Column(name = "client_amount_max_ratio")
    private Double clientAmountMaxRatio;
    
    @Column(name = "client_is_new_max")
    private Boolean clientIsNewMax;
    
    @Column(name = "client_is_new_min")
    private Boolean clientIsNewMin;
    
    @Column(name = "client_time_since_last_tx")
    private Long clientTimeSinceLastTx;
    
    @Column(name = "client_hours_since_last_tx")
    private Double clientHoursSinceLastTx;
    
    @Column(name = "client_usual_region")
    private String clientUsualRegion;
    
    @Column(name = "client_usual_channel")
    private String clientUsualChannel;
    
    @Column(name = "client_usual_category")
    private String clientUsualCategory;
    
    @Column(name = "client_region_freq")
    private Double clientRegionFreq;
    
    @Column(name = "client_channel_freq")
    private Double clientChannelFreq;
    
    @Column(name = "client_category_freq")
    private Double clientCategoryFreq;
    
    @Column(name = "client_is_unusual_region")
    private Boolean clientIsUnusualRegion;
    
    @Column(name = "client_is_unusual_channel")
    private Boolean clientIsUnusualChannel;
    
    @Column(name = "client_is_unusual_category")
    private Boolean clientIsUnusualCategory;
    
    @Column(name = "client_risk_score")
    private Double clientRiskScore;
    
    @Column(name = "last_calculated_at")
    private LocalDateTime lastCalculatedAt;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}