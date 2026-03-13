package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetCleanResponse;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.DatasetStatus;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.service.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetAnalysisPythonService {

    private final DatasetRepository datasetRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    @Transactional
    public DatasetAnalysisResponse analyzeDataset(UUID datasetUuid) throws Exception {
        Dataset dataset = datasetRepository.findByDatasetUuid(datasetUuid)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetUuid));

        Path tempFile = null;
        String containerFileName = null;

        try {
            // 1. Télécharger le fichier depuis MinIO vers un fichier temporaire LOCAL
            tempFile = Files.createTempFile("dataset_", ".csv");
            try (InputStream in = storageService.download(dataset.getFilePath());
                 OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }

            // 2. Créer le dossier /app/data dans le conteneur s'il n'existe pas
            ProcessBuilder mkdirPb = new ProcessBuilder(
                    "docker", "exec", "fraud-ml-pipeline", "mkdir", "-p", "/app/data");
            mkdirPb.start().waitFor();

            // 3. Copier le fichier dans le conteneur ml-pipeline
            containerFileName = "/app/data/" + tempFile.getFileName().toString();
            ProcessBuilder copyPb = new ProcessBuilder(
                    "docker", "cp", tempFile.toAbsolutePath().toString(),
                    "fraud-ml-pipeline:" + containerFileName);

            Process copyProcess = copyPb.start();
            int copyExitCode = copyProcess.waitFor();

            if (copyExitCode != 0) {
                String copyError = readStream(copyProcess.getErrorStream());
                throw new RuntimeException("Failed to copy file to ml-pipeline container: " + copyError);
            }

            // 4. Lancer l'analyse dans le conteneur ml-pipeline
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", "fraud-ml-pipeline",
                    "python", "-m", "ml_pipeline.cli.main",
                    "analyze-dataset",
                    "--dataset-path", containerFileName);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 5. Lire toute la sortie
            StringBuilder fullOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    fullOutput.append(line).append("\n");
                }
            }

            // 6. Attendre la fin du processus
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Python process timed out after 5 minutes");
            }

            int exitCode = process.exitValue();
            String output = fullOutput.toString().trim();

            if (exitCode != 0) {
                log.error("Python process exited with code {}: {}", exitCode, output);
                throw new RuntimeException("Python analysis failed with code " + exitCode);
            }

            if (output.isEmpty()) {
                throw new RuntimeException("No output from Python process");
            }

            // 7. Extraire le JSON de la sortie
            JsonNode root = extractJsonFromOutput(output);

            if (root == null) {
                throw new RuntimeException("No valid JSON found in Python output: " + output);
            }

            if (root.has("error")) {
                throw new RuntimeException("Python error: " + root.get("error").asText());
            }

            // 8. Convertir en DatasetAnalysisResponse
            DatasetAnalysisResponse response = parseAnalysisResponse(root, dataset);

            // 9. Sauvegarder les résultats dans le dataset
            Map<String, Object> featureCols = new HashMap<>();
            featureCols.put("columns", response.getColumns());
            featureCols.put("suggestedFeatures", response.getSuggestedFeatures());
            featureCols.put("suggestedTarget", response.getSuggestedTarget());
            
            // Ajouter aussi les nullRatios pour chaque colonne (utile pour le nettoyage)
            Map<String, Double> nullRatios = new HashMap<>();
            response.getColumns().forEach(col -> 
                nullRatios.put(col.getName(), col.getNullRatio())
            );
            featureCols.put("nullRatios", nullRatios);
            
            dataset.setFeatureColumns(featureCols);
            dataset.setStatistics(response.getStatistics());
            datasetRepository.save(dataset);

            log.info("Analyse terminée avec succès pour le dataset: {}", dataset.getDatasetCode());
            return response;

        } catch (Exception e) {
            log.error("Erreur lors de l'analyse du dataset", e);
            throw e;
        } finally {
            cleanup(tempFile, containerFileName);
        }
    }

    // ==================== NETTOYAGE INTELLIGENT AVEC RÉSULTATS D'ANALYSE ====================

    @Transactional
    public DatasetCleanResponse cleanDatasetOptimized(UUID datasetUuid) throws Exception {
        Dataset dataset = datasetRepository.findByDatasetUuid(datasetUuid)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetUuid));

        // Récupérer les résultats d'analyse existants
        Map<String, Object> analysisResults = dataset.getFeatureColumns();
        String jobId = UUID.randomUUID().toString();

        Path tempFile = null;
        Path analysisFile = null;
        Path cleanedFile = null;
        String containerFileName = null;
        String containerAnalysisFile = null;
        String containerOutputFile = null;

        try {
            log.info("Début du nettoyage optimisé pour le dataset: {}", datasetUuid);
            
            // 1. Télécharger le CSV depuis MinIO
            tempFile = Files.createTempFile("dataset_clean_", ".csv");
            try (InputStream in = storageService.download(dataset.getFilePath());
                 OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }
            log.info("Fichier CSV téléchargé: {}", tempFile);

            // 2. Créer un fichier temporaire avec les résultats d'analyse
            if (analysisResults != null && !analysisResults.isEmpty()) {
                analysisFile = Files.createTempFile("analysis_", ".json");
                objectMapper.writeValue(analysisFile.toFile(), analysisResults);
                log.info("Fichier d'analyse créé: {}", analysisFile);
            } else {
                log.warn("Aucun résultat d'analyse trouvé pour le dataset {}", datasetUuid);
            }

            // 3. Préparer le conteneur
            execDockerCommand("mkdir", "-p", "/app/data");
            execDockerCommand("mkdir", "-p", "/app/output");

            // 4. Copier les fichiers dans le conteneur
            containerFileName = "/app/data/" + tempFile.getFileName().toString();
            execDockerCopy(tempFile, containerFileName);
            log.info("Fichier CSV copié dans le conteneur: {}", containerFileName);

            // 5. Préparer la commande Python
            List<String> pythonArgs = new ArrayList<>();
            pythonArgs.add("python");
            pythonArgs.add("/app/scripts/csv_to_parquet_ingest.py");  // Votre script existant
            pythonArgs.add("--input");
            pythonArgs.add(containerFileName);
            pythonArgs.add("--dataset-id");
            pythonArgs.add(datasetUuid.toString());
            pythonArgs.add("--job-id");
            pythonArgs.add(jobId);

            // Ajouter les résultats d'analyse si disponibles
            if (analysisFile != null) {
                containerAnalysisFile = "/app/data/" + analysisFile.getFileName().toString();
                execDockerCopy(analysisFile, containerAnalysisFile);
                pythonArgs.add("--analysis-json");
                pythonArgs.add(containerAnalysisFile);
                log.info("Fichier d'analyse copié dans le conteneur: {}", containerAnalysisFile);
            }

            // 6. Lancer le script Python
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", "fraud-ml-pipeline");
            pb.command().addAll(pythonArgs);
            pb.redirectErrorStream(true);

            log.info("Exécution de la commande: {}", String.join(" ", pb.command()));
            Process process = pb.start();

            // Lire la sortie en temps réel pour le logging
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("[Python] {}", line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Python cleaning timed out after 10 minutes");
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();

            if (exitCode != 0) {
                log.error("Python process failed with code {}: {}", exitCode, result);
                throw new RuntimeException("Python cleaning failed with code " + exitCode);
            }

            // 7. Parser le résultat JSON
            JsonNode root = extractJsonFromOutput(result);
            if (root == null) {
                throw new RuntimeException("No valid JSON output from Python script: " + result);
            }
            if (root.has("error")) {
                throw new RuntimeException("Python cleaning error: " + root.get("error").asText());
            }

            // 8. Récupérer le fichier Parquet nettoyé depuis le conteneur
            String cleanedFilename = "cleaned_" + datasetUuid + ".parquet";
            containerOutputFile = "/app/output/" + cleanedFilename;

            cleanedFile = Files.createTempFile("cleaned_", ".parquet");
            execDockerCopyFromContainer(containerOutputFile, cleanedFile);
            log.info("Fichier nettoyé récupéré: {}", cleanedFile);

            // 9. Upload vers MinIO
            String minioKey = "data/processed/transactions/" + datasetUuid + "/" + cleanedFilename;
            try (InputStream cleanedInputStream = Files.newInputStream(cleanedFile)) {
                storageService.upload(
                        minioKey,
                        cleanedInputStream,
                        Files.size(cleanedFile),
                        "application/octet-stream");
            }
            log.info("Fichier uploadé vers MinIO: {}", minioKey);

            // 10. Mapper les statistiques de nettoyage
            JsonNode statsNode = root.get("cleaning_stats");
            DatasetCleanResponse.CleaningStats cleaningStats = DatasetCleanResponse.CleaningStats.builder()
                    .originalRows(statsNode.path("original_rows").asLong())
                    .cleanedRows(statsNode.path("cleaned_rows").asLong())
                    .duplicatesRemoved(statsNode.path("duplicates_removed").asLong())
                    .nullRowsRemoved(statsNode.path("null_rows_removed").asLong())
                    .reductionPercent(statsNode.path("reduction_percent").asDouble())
                    .fraudCount(statsNode.path("fraud_count_after").asLong())
                    .fraudRate(statsNode.path("fraud_rate").asDouble())
                    .build();

            // 11. Récupérer les colonnes supprimées si présentes
            List<String> columnsRemoved = new ArrayList<>();
            if (root.has("cleaning_stats") && root.get("cleaning_stats").has("columns_removed")) {
                columnsRemoved = objectMapper.convertValue(
                    root.get("cleaning_stats").get("columns_removed"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                );
            }

            // 12. Mettre à jour le dataset
            dataset.setFilePath(minioKey);
            dataset.setStatus(DatasetStatus.PROCESSED);
            
            // Ajouter les stats de nettoyage aux featureColumns
            Map<String, Object> featureCols = dataset.getFeatureColumns();
            if (featureCols == null) {
                featureCols = new HashMap<>();
            }
            
            Map<String, Object> cleaningStatsMap = new HashMap<>();
            cleaningStatsMap.put("originalRows", cleaningStats.getOriginalRows());
            cleaningStatsMap.put("cleanedRows", cleaningStats.getCleanedRows());
            cleaningStatsMap.put("duplicatesRemoved", cleaningStats.getDuplicatesRemoved());
            cleaningStatsMap.put("nullRowsRemoved", cleaningStats.getNullRowsRemoved());
            cleaningStatsMap.put("reductionPercent", cleaningStats.getReductionPercent());
            cleaningStatsMap.put("fraudCount", cleaningStats.getFraudCount());
            cleaningStatsMap.put("fraudRate", cleaningStats.getFraudRate());
            cleaningStatsMap.put("columnsRemoved", columnsRemoved);
            cleaningStatsMap.put("jobId", jobId);
            cleaningStatsMap.put("cleanedAt", LocalDateTime.now().toString());
            
            featureCols.put("cleaning_stats", cleaningStatsMap);
            dataset.setFeatureColumns(featureCols);
            
            datasetRepository.save(dataset);

            log.info("Nettoyage optimisé terminé avec succès - dataset: {}, lignes conservées: {}/{} ({}%)", 
                datasetUuid, 
                cleaningStats.getCleanedRows(), 
                cleaningStats.getOriginalRows(),
                String.format("%.2f", 100 - cleaningStats.getReductionPercent()));

            // 13. Construire la réponse
            return DatasetCleanResponse.builder()
                    .datasetUuid(dataset.getDatasetUuid())
                    .datasetName(dataset.getDatasetName())
                    .status(DatasetCleanResponse.CleaningStatus.COMPLETED)
                    .jobId(jobId)
                    .processedFilePath(minioKey)
                    .stats(cleaningStats)
                    .build();

        } catch (Exception e) {
            log.error("Erreur lors du nettoyage optimisé du dataset {}", datasetUuid, e);
            throw e;
        } finally {
            // Nettoyage des fichiers temporaires
            cleanup(tempFile, containerFileName);
            
            if (analysisFile != null) {
                try { 
                    Files.deleteIfExists(analysisFile); 
                    log.debug("Fichier d'analyse temporaire supprimé");
                } catch (Exception e) { 
                    log.warn("Suppression analysis file failed: {}", e.getMessage()); 
                }
            }
            
            if (containerAnalysisFile != null) {
                try { 
                    execDockerCommand("rm", "-f", containerAnalysisFile);
                    log.debug("Fichier d'analyse conteneur supprimé");
                } catch (Exception e) { 
                    log.warn("Nettoyage conteneur analysis file failed: {}", e.getMessage()); 
                }
            }
            
            if (containerOutputFile != null) {
                try { 
                    execDockerCommand("rm", "-f", containerOutputFile);
                    log.debug("Fichier output conteneur supprimé");
                } catch (Exception e) { 
                    log.warn("Nettoyage conteneur output failed: {}", e.getMessage()); 
                }
            }
            
            if (cleanedFile != null) {
                try { 
                    Files.deleteIfExists(cleanedFile);
                    log.debug("Fichier nettoyé temporaire supprimé");
                } catch (Exception e) { 
                    log.warn("Suppression cleaned file failed: {}", e.getMessage()); 
                }
            }
        }
    }

    // ==================== MÉTHODE DE NETTOYAGE STANDARD (BACKWARD COMPATIBILITY) ====================

    @Transactional
    public DatasetCleanResponse cleanDataset(UUID datasetUuid) throws Exception {
        // Par défaut, utiliser la version optimisée
        return cleanDatasetOptimized(datasetUuid);
    }

    // ==================== MÉTHODES PRIVÉES ====================

    private String execDockerCommand(String... commands) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add("fraud-ml-pipeline");
        cmd.addAll(Arrays.asList(commands));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Docker command failed: " + output);
        }

        return output;
    }

    private void execDockerCopy(Path source, String destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp", source.toAbsolutePath().toString(),
                "fraud-ml-pipeline:" + destination);

        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Docker copy failed: " + output);
        }
    }

    private void execDockerCopyFromContainer(String containerPath, Path localPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp",
                "fraud-ml-pipeline:" + containerPath,
                localPath.toAbsolutePath().toString());

        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Docker copy from container failed: " + output);
        }
    }

    private JsonNode extractJsonFromOutput(String output) {
        // Essayer de parser directement
        try {
            return objectMapper.readTree(output);
        } catch (Exception e) {
            log.debug("Sortie non-JSON directe, tentative d'extraction...");
        }

        // Chercher le premier objet JSON valide
        int jsonStart = output.indexOf('{');
        int jsonEnd = output.lastIndexOf('}');

        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                String jsonStr = output.substring(jsonStart, jsonEnd + 1);
                return objectMapper.readTree(jsonStr);
            } catch (Exception e2) {
                log.debug("Extraction JSON échouée: {}", e2.getMessage());
            }
        }

        return null;
    }

    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private DatasetAnalysisResponse parseAnalysisResponse(JsonNode root, Dataset dataset) {
        List<DatasetAnalysisResponse.ColumnInfo> columns = new ArrayList<>();
        JsonNode colsNode = root.get("columns");
        if (colsNode != null && colsNode.isArray()) {
            for (JsonNode colNode : colsNode) {
                DatasetAnalysisResponse.ColumnInfo colInfo = DatasetAnalysisResponse.ColumnInfo.builder()
                        .name(colNode.get("name").asText())
                        .type(colNode.get("type").asText())
                        .uniqueCount(colNode.get("uniqueCount").asInt())
                        .nullRatio(colNode.get("nullRatio").asDouble())
                        .stats(objectMapper.convertValue(colNode.get("stats"), Map.class))
                        .isTarget(false)
                        .build();
                columns.add(colInfo);
            }
        }

        JsonNode statsNode = root.get("statistics");
        Map<String, Object> statistics = statsNode != null
                ? objectMapper.convertValue(statsNode, Map.class)
                : Map.of();

        List<String> suggestedFeatures = objectMapper.convertValue(root.get("suggestedFeatures"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

        String suggestedTarget = root.has("suggestedTarget") ? root.get("suggestedTarget").asText() : null;

        if (suggestedTarget != null) {
            columns.stream()
                    .filter(c -> c.getName().equals(suggestedTarget))
                    .findFirst()
                    .ifPresent(c -> c.setIsTarget(true));
        }

        List<Map<String, Object>> preview = objectMapper.convertValue(root.get("preview"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));

        int sampleRows = root.has("sampleRows") ? root.get("sampleRows").asInt() : 0;

        return DatasetAnalysisResponse.builder()
                .datasetUuid(dataset.getDatasetUuid())
                .datasetName(dataset.getDatasetName())
                .status(dataset.getStatus().toString())
                .columns(columns)
                .statistics(statistics)
                .suggestedFeatures(suggestedFeatures)
                .suggestedTarget(suggestedTarget)
                .sampleRows(sampleRows)
                .preview(preview)
                .build();
    }

    private void cleanup(Path tempFile, String containerFileName) {
        if (containerFileName != null) {
            try { 
                execDockerCommand("rm", "-f", containerFileName);
                log.debug("Fichier conteneur supprimé: {}", containerFileName);
            } catch (Exception e) { 
                log.warn("Nettoyage conteneur échoué: {}", e.getMessage()); 
            }
        }
        if (tempFile != null) {
            try { 
                Files.deleteIfExists(tempFile);
                log.debug("Fichier temporaire supprimé: {}", tempFile);
            } catch (Exception e) { 
                log.warn("Suppression fichier temporaire échouée: {}", e.getMessage()); 
            }
        }
    }
}