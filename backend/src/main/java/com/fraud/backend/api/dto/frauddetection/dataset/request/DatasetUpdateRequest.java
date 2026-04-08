package com.fraud.backend.api.dto.frauddetection.dataset.request;

import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;

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
    private DatasetType type;
    private Boolean visibility;
    private Long assignedManagerId;
}