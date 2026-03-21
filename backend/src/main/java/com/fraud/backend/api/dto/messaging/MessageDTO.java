package com.fraud.backend.api.dto.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class MessageDTO {
    private UUID messageUuid;
    private Long senderId;
    private String senderEmail;
    private String senderName;
    private Long receiverId;
    private String receiverEmail;
    private String receiverName;
    private String subject;
    private String content;
    private String messageType;

    @JsonProperty("isRead")
    private boolean isRead;

    @JsonProperty("isArchived")
    private boolean isArchived;

    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    private UUID parentMessageUuid;
}