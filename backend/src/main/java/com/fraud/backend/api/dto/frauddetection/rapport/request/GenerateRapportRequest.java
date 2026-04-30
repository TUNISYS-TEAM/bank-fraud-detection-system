package com.fraud.backend.api.dto.frauddetection.rapport.request;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class GenerateRapportRequest {
    private String title;
    private String type; // DAILY, WEEKLY, MONTHLY, ON_DEMAND
    private String description;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private List<Long> recipientIds;
}