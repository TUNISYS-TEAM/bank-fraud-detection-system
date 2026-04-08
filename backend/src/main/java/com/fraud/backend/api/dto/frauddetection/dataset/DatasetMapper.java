package com.fraud.backend.api.dto.frauddetection.dataset;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class DatasetMapper {

    public DatasetResponse toResponse(Dataset d) {
        if (d == null) return null;

        DatasetResponse.DatasetResponseBuilder builder = DatasetResponse.builder()
                .datasetUuid(d.getDatasetUuid())
                .datasetCode(d.getDatasetCode())
                .datasetName(d.getDatasetName())
                .fileSizeMb(d.getFileSizeMb())
                .rowCount(d.getRowCount())
                .status(d.getStatus())
                .type(d.getType())
                .visibility(d.getVisibility())
                .importedAt(d.getImportedAt());

        // Map assigned manager info (avoid LAZY loading issues with null-check)
        AppUser manager = d.getAssignedManager();
        if (manager != null) {
            builder.assignedManagerId(manager.getId())
                   .assignedManagerName(manager.getFirstName() + " " + manager.getLastName())
                   .assignedManagerEmail(manager.getEmail());
        }

        return builder.build();
    }

    public void updateEntity(DatasetUpdateRequest request, Dataset dataset) {
        if (request.getDatasetName() != null) {
            dataset.setDatasetName(request.getDatasetName());
        }
        if (request.getStatus() != null) {
            dataset.setStatus(request.getStatus());
            // Auto-update visibility based on status
            if (request.getStatus() == DatasetStatus.CLEANED || 
                request.getStatus() == DatasetStatus.PROCESSED) {
                dataset.setVisibility(true);
            }
        }
        if (request.getType() != null) {
            dataset.setType(request.getType());
        }
        if (request.getVisibility() != null) {
            dataset.setVisibility(request.getVisibility());
        }
    }
}