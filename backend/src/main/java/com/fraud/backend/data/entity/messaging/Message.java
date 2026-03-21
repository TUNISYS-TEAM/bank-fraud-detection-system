package com.fraud.backend.data.entity.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private AppUser sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id")
    private AppUser receiver;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Builder.Default
    @Column(name = "message_type")
    private String messageType = "GENERAL";

    @JsonProperty("isRead")
    @Column(name = "is_read")
    private Boolean isRead;

    @Builder.Default
    @Column(name = "is_archived")
    private Boolean isArchived = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_message_id")
    private Message parentMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (messageUuid == null) {
            messageUuid = UUID.randomUUID();
        }
        if (isRead == null) {
            isRead = false;
        }
        if (isArchived == null) {
            isArchived = false;
        }
    }
}