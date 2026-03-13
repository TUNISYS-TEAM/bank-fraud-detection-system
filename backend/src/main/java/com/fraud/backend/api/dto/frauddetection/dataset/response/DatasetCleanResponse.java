package com.fraud.backend.api.dto.frauddetection.dataset.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class DatasetCleanResponse {
    private UUID datasetUuid;
    private String datasetName;
    private CleaningStatus status;
    private String jobId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String processedFilePath; // Chemin MinIO du fichier nettoyé
    
    // Statistiques de nettoyage (optionnelles)
    private CleaningStats stats;
    private IngestionStats ingestionStats;
    
    // Fichiers générés (optionnels)
    private String cleanedParquetPath;
    private String metadataPath;
    
    @Data
    @Builder
    public static class CleaningStats {
        private long originalRows;
        private long cleanedRows;
        private long duplicatesRemoved;
        private long nullRowsRemoved;
        private double reductionPercent;
        private long fraudCount;
        private double fraudRate;
        private Map<String, Long> missingValuesByColumn;
        private List<String> droppedColumns;
    }
    
    @Data
    @Builder
    public static class IngestionStats {
        private long rowsIngested;
        private long fraudRows;
        private double fraudRate;
        private String tableName;
        private double ingestionTimeSeconds;
        private String batchId;
    }
    
    public enum CleaningStatus {
        NOT_STARTED, 
        VALIDATING, 
        CLEANING, 
        CONVERTING, 
        INGESTING, 
        COMPLETED, 
        FAILED
    }
    
    // Méthodes utilitaires pour créer des réponses simplifiées
    public static DatasetCleanResponse simpleResponse(UUID datasetUuid, String datasetName, 
                                                      CleaningStatus status, String jobId) {
        return DatasetCleanResponse.builder()
                .datasetUuid(datasetUuid)
                .datasetName(datasetName)
                .status(status)
                .jobId(jobId)
                .build();
    }
    
    public static DatasetCleanResponse completedResponse(UUID datasetUuid, String datasetName, 
                                                         String jobId, String processedFilePath) {
        return DatasetCleanResponse.builder()
                .datasetUuid(datasetUuid)
                .datasetName(datasetName)
                .status(CleaningStatus.COMPLETED)
                .jobId(jobId)
                .processedFilePath(processedFilePath)
                .startTime(LocalDateTime.now().minusSeconds(30))
                .endTime(LocalDateTime.now())
                .build();
    }
    
    public static DatasetCleanResponse failedResponse(UUID datasetUuid, String datasetName, 
                                                      String jobId, String error) {
        return DatasetCleanResponse.builder()
                .datasetUuid(datasetUuid)
                .datasetName(datasetName)
                .status(CleaningStatus.FAILED)
                .jobId(jobId)
                .build();
    }
}