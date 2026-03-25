package com.fraud.backend.api.dto.frauddetection.fraudalert.response;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataResponse {
    private String date;
    private Long count;
    private Long criticalCount;
    private Long highCount;
    private Long mediumCount;
    private Long lowCount;
}