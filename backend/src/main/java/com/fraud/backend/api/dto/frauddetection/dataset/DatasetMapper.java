package com.fraud.backend.api.dto.frauddetection.dataset;

import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetResponse;
import org.springframework.stereotype.Component;

@Component
public class DatasetMapper {

    public DatasetResponse toResponse(Dataset d) {
        if (d == null) return null;

        return DatasetResponse.builder()
                .datasetUuid(d.getDatasetUuid())
                .datasetCode(d.getDatasetCode())
                .datasetName(d.getDatasetName())
                .fileSizeMb(d.getFileSizeMb())
                .rowCount(d.getRowCount())
                .status(d.getStatus())
                .type(d.getType())
                .importedAt(d.getImportedAt())
                .build();
    }
}