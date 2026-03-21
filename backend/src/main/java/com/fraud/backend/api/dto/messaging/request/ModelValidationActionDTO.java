package com.fraud.backend.api.dto.messaging.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelValidationActionDTO {
    private UUID modelUuid;
    private String status; // Validated or Rejected
    private String comment;
}