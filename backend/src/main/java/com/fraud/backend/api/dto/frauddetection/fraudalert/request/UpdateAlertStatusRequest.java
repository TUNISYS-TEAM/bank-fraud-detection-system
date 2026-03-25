package com.fraud.backend.api.dto.frauddetection.fraudalert.request;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAlertStatusRequest {
    private String status;
    private String reviewComment;
    private String actionTaken;
}