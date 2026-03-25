package com.fraud.backend.api.dto.accessmanagement.system.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemHealthResponse {
    private double healthScore;
    private String status;
    private LocalDateTime timestamp;
    private String message;
}