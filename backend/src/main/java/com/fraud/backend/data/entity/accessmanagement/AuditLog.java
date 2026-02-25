package com.fraud.backend.data.entity.accessmanagement;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private UserSession session;

    private String action;

    @Column(name = "action_type")
    private String actionType;

    @CreationTimestamp
    @Column(name = "action_timestamp", updatable = false)
    private LocalDateTime actionTimestamp;

    private String level;
}