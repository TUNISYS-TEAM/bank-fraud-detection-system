package com.fraud.backend.api.dto.messaging.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SendMessageRequest {
    
    @NotNull(message = "Receiver ID is required")
    private Long receiverId;
    
    private String subject;
    
    @NotBlank(message = "Content is required")
    private String content;
    
    private UUID parentMessageUuid;
}