package com.fraud.backend.api.dto.frauddetection.fraudalert.response;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertStatsResponse {
    private Long total;
    private Long critical;
    private Long high;
    private Long medium;
    private Long low;
    private Long pending;
    private Long reviewed;
    private Long resolved;
    private Long falsePositive;
}