package com.fraud.backend.api.dto.frauddetection.dataset.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DatasetResponse {
    private UUID datasetUuid;
    private String datasetCode;
    private String datasetName;

    private BigDecimal fileSizeMb;
    private Long rowCount;

    private DatasetStatus status;
    private DatasetType type;
    private LocalDateTime importedAt;
}