package com.fraud.backend.data.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "keycloak_mapping")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KeycloakMapping {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "keycloak_id", unique = true)
    private String keycloakId;
    
    @ManyToOne
    @JoinColumn(name = "user_id")
    private AppUser user;
    
    private String username;
    private String email;
    
    @Column(name = "last_sync")
    private LocalDateTime lastSync;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}