package com.fraud.backend.api.dto.frauddetection.dataset.response;

import lombok.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DatasetPreviewResponse {
    private UUID datasetUuid;
    private String datasetName;

    private List<String> columns;                 // header CSV
    private List<Map<String, String>> rows;       // N lignes sous forme clé/valeur
    private int returnedRows;                     // nb lignes renvoyées
    private int limit;                            // limit demandé
}