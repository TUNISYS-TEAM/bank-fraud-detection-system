package com.fraud.backend.data.entity.corebanking;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "client_behavior_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientBehaviorHistory {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "client_id")
    private Client client;
    
    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "behavior_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> behaviorSnapshot;
    
    @ManyToOne
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;
    
    @Column(name = "calculation_date")
    private LocalDateTime calculationDate;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}