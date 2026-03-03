package com.fraud.backend.api.dto.frauddetection.dataset.request;

import com.fraud.backend.data.enums.DatasetStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatasetUpdateRequest {
    private String datasetName;
    private DatasetStatus status;
}