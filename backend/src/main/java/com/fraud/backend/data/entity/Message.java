package com.fraud.backend.data.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "message")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "message_uuid", unique = true)
    private UUID messageUuid;
    
    @ManyToOne
    @JoinColumn(name = "sender_id")
    private AppUser sender;
    
    @ManyToOne
    @JoinColumn(name = "receiver_id")
    private AppUser receiver;
    
    private String subject;
    
    @Column(columnDefinition = "TEXT")
    private String content;
    
    @Column(name = "is_read")
    private Boolean isRead;
    
    @Column(name = "read_at")
    private LocalDateTime readAt;
    
    @ManyToOne
    @JoinColumn(name = "parent_message_id")
    private Message parentMessage;
    
    private String priority;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}