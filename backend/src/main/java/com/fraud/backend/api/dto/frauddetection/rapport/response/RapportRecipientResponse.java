package com.fraud.backend.api.dto.frauddetection.rapport.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RapportRecipientResponse {
    private Long recipientId;
    private String recipientName;
    private String recipientRole;
    @JsonProperty("isRead")
    private boolean isRead;
    private LocalDateTime readAt;
}