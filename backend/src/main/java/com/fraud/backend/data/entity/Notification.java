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
@Table(name = "notification")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "notification_uuid", unique = true)
    private UUID notificationUuid;
    
    @ManyToOne
    @JoinColumn(name = "user_id")
    private AppUser user;
    
    private String title;
    
    @Column(columnDefinition = "TEXT")
    private String message;
    
    private String type;
    
    @Column(name = "is_read")
    private Boolean isRead;
    
    @Column(name = "read_at")
    private LocalDateTime readAt;
    
    @Column(name = "action_url")
    private String actionUrl;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}