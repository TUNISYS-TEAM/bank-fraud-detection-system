package com.fraud.backend.api.dto.frauddetection.dataset.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class DatasetAnalysisResponse {
    private UUID datasetUuid;
    private String datasetName;
    private String status;
    private List<ColumnInfo> columns;
    private Map<String, Object> statistics;
    private List<String> suggestedFeatures;
    private String suggestedTarget;
    private Integer sampleRows;
    private List<Map<String, Object>> preview;

    @Data
    @Builder
    public static class ColumnInfo {
        private String name;
        private String type; // NUMERIC, CATEGORICAL, DATE, BINARY, TEXT
        private Integer uniqueCount;
        private Double nullRatio;
        private Map<String, Object> stats;
        private Boolean isTarget;
    }
}