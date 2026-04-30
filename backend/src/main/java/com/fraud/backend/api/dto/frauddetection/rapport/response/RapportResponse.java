package com.fraud.backend.api.dto.frauddetection.rapport.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RapportResponse {
    private Long id;
    private UUID rapportUuid;
    private String title;
    private String type;
    private String format;
    private String description;
    private String status;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Integer totalTransactions;
    private Integer fraudDetected;
    private Double avgFraudScore;
    private Map<String, Object> dataSummary;
    private LocalDateTime createdAt;
    private String generatedByName;
    private String generatedByEmail;
    private Long generatedById;
    private String generatedByRole;
    @JsonProperty("isRead")
    private boolean isRead; // For the current user
    private List<RapportRecipientResponse> recipients;
}