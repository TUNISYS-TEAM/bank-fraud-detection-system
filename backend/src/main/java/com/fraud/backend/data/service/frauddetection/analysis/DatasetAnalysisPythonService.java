package com.fraud.backend.data.service.frauddetection.analysis;

import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetCleanResponse;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.service.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetAnalysisPythonService {

    private final DatasetRepository datasetRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${fraud.ml.onnx.container-name:fraud-ml-onnx}")
    private String onnxContainerName;

    private static final long ANALYSIS_TIMEOUT_MINUTES = 5;
    private static final long CLEANING_TIMEOUT_MINUTES = 10;

    // ==================== ANALYSE ====================

    @Transactional
    public DatasetAnalysisResponse analyzeDataset(UUID datasetUuid) throws Exception {
        Dataset dataset = datasetRepository.findByDatasetUuid(datasetUuid)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetUuid));

        // Detect file format from stored path
        String storedPath = dataset.getFilePath();
        boolean isParquet = storedPath != null && storedPath.toLowerCase().endsWith(".parquet");
        String extension = isParquet ? ".parquet" : ".csv";

        log.info("Analysing dataset={} format={}", datasetUuid, isParquet ? "parquet" : "csv");

        Path tempFile = null;
        Path utf8File = null;
        String containerFileName = null;

        try {
            // 1. Download file from MinIO into a temp file with the correct extension
            tempFile = Files.createTempFile("dataset_", extension);
            try (InputStream in = storageService.download(storedPath);
                    OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }
            log.info("Dataset downloaded to temp file: {}", tempFile);

            // 2. UTF-8 conversion only applies to CSV files
            if (isParquet) {
                utf8File = tempFile;
                log.info("Parquet file — skipping UTF-8 conversion");
            } else {
                Path converted = convertToUtf8(tempFile);
                utf8File = (converted != null) ? converted : tempFile;
                log.info(converted != null ? "File converted to UTF-8: {}" : "File already UTF-8: {}", utf8File);
            }

            // 3. Prepare container directory (use /tmp to avoid local volume leak)
            execDockerCommand("mkdir", "-p", "/tmp/fraud-work");

            // 4. Copy file into container
            containerFileName = "/tmp/fraud-work/" + utf8File.getFileName().toString();
            execDockerCopy(utf8File, containerFileName);
            log.info("File copied to container: {}", containerFileName);

            // 5. Run analysis — Python already handles both CSV and Parquet
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", onnxContainerName,
                    "python", "/shared-ml/scripts/analyze_dataset.py",
                    containerFileName);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder fullOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    fullOutput.append(line).append("\n");
                    log.debug("[Python] {}", line);
                }
            }

            boolean finished = process.waitFor(ANALYSIS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Python process timed out after " + ANALYSIS_TIMEOUT_MINUTES + " minutes");
            }

            int exitCode = process.exitValue();
            String output = fullOutput.toString().trim();

            if (exitCode != 0) {
                log.error("Python process exited with code {}: {}", exitCode, output);
                throw new RuntimeException("Python analysis failed with code " + exitCode);
            }

            JsonNode root = extractJsonFromOutput(output);
            if (root == null) {
                throw new RuntimeException("No valid JSON found in Python output: " + output);
            }
            if (root.has("error")) {
                throw new RuntimeException("Python error: " + root.get("error").asText());
            }

            DatasetAnalysisResponse response = parseAnalysisResponse(root, dataset);

            // Persist analysis results
            Map<String, Object> featureCols = new HashMap<>();
            featureCols.put("columns", response.getColumns());
            featureCols.put("suggestedFeatures", response.getSuggestedFeatures());
            featureCols.put("suggestedTarget", response.getSuggestedTarget());

            Map<String, Double> nullRatios = new HashMap<>();
            response.getColumns().forEach(col -> nullRatios.put(col.getName(), col.getNullRatio()));
            featureCols.put("nullRatios", nullRatios);
            featureCols.put("analyzedAt", LocalDateTime.now().toString());

            dataset.setFeatureColumns(featureCols);
            // Merge new statistics with existing ones (preserve cloned_from_uuid etc.)
            Map<String, Object> existingStats = dataset.getStatistics();
            Map<String, Object> newStats = response.getStatistics();
            if (existingStats != null && newStats != null) {
                existingStats.putAll(newStats);
                dataset.setStatistics(existingStats);
            } else {
                dataset.setStatistics(newStats);
            }
            datasetRepository.save(dataset);

            log.info("Analyse terminée avec succès pour le dataset: {}", dataset.getDatasetCode());
            return response;

        } catch (Exception e) {
            log.error("Erreur lors de l'analyse du dataset", e);
            throw e;
        } finally {
            cleanup(tempFile, containerFileName);
            if (utf8File != null && !utf8File.equals(tempFile)) {
                try {
                    Files.deleteIfExists(utf8File);
                } catch (Exception e) {
                    log.warn("Nettoyage utf8 file failed: {}", e.getMessage());
                }
            }
        }
    }

    // ==================== NETTOYAGE ====================

    @Transactional
    public DatasetCleanResponse cleanDataset(UUID datasetUuid) throws Exception {
        log.info("Démarrage du nettoyage standard pour dataset: {}", datasetUuid);
        return cleanDatasetOptimized(datasetUuid);
    }

    @Transactional
    public DatasetCleanResponse cleanDatasetOptimized(UUID datasetUuid) throws Exception {
        Dataset dataset = datasetRepository.findByDatasetUuid(datasetUuid)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetUuid));

        Map<String, Object> analysisResults = dataset.getFeatureColumns();
        String jobId = UUID.randomUUID().toString();

        // Detect format
        String storedPath = dataset.getFilePath();
        boolean isParquet = storedPath != null && storedPath.toLowerCase().endsWith(".parquet");
        String extension = isParquet ? ".parquet" : ".csv";

        log.info("Cleaning dataset={} format={}", datasetUuid, isParquet ? "parquet" : "csv");

        Path tempFile = null;
        Path utf8File = null;
        Path analysisFile = null;
        Path cleanedFile = null;
        String containerFileName = null;
        String containerAnalysisFile = null;
        String containerOutputFile = null;

        try {
            // 1. Download with correct extension
            tempFile = Files.createTempFile("dataset_clean_", extension);
            try (InputStream in = storageService.download(storedPath);
                    OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }
            log.info("Fichier téléchargé: {}", tempFile);

            // 2. UTF-8 conversion only for CSV
            if (isParquet) {
                utf8File = tempFile;
                log.info("Parquet file — skipping UTF-8 conversion");
            } else {
                Path converted = convertToUtf8(tempFile);
                utf8File = (converted != null) ? converted : tempFile;
                log.info(converted != null ? "Fichier converti en UTF-8: {}" : "Fichier déjà UTF-8: {}", utf8File);
            }

            // 3. Write analysis JSON if available
            if (analysisResults != null && !analysisResults.isEmpty()) {
                analysisFile = Files.createTempFile("analysis_", ".json");
                objectMapper.writeValue(analysisFile.toFile(), analysisResults);
                log.info("Fichier d'analyse créé: {}", analysisFile);
            }

            execDockerCommand("mkdir", "-p", "/tmp/fraud-work");
            execDockerCommand("mkdir", "-p", "/tmp/fraud-work/output");

            // 4. Copy data file into container
            containerFileName = "/tmp/fraud-work/" + utf8File.getFileName().toString();
            execDockerCopy(utf8File, containerFileName);
            log.info("Fichier copié dans le conteneur: {}", containerFileName);

            // 5. Build Python command
            List<String> pythonArgs = new ArrayList<>();
            pythonArgs.add("python");
            pythonArgs.add("/shared-ml/scripts/csv_to_parquet_ingest.py");
            pythonArgs.add("--input");
            pythonArgs.add(containerFileName);
            pythonArgs.add("--dataset-id");
            pythonArgs.add(datasetUuid.toString());
            pythonArgs.add("--job-id");
            pythonArgs.add(jobId);

            if (analysisFile != null) {
                containerAnalysisFile = "/tmp/fraud-work/" + analysisFile.getFileName().toString();
                execDockerCopy(analysisFile, containerAnalysisFile);
                pythonArgs.add("--analysis-json");
                pythonArgs.add(containerAnalysisFile);
                log.info("Fichier d'analyse copié dans le conteneur: {}", containerAnalysisFile);
            }

            ProcessBuilder pb = new ProcessBuilder("docker", "exec", onnxContainerName);
            pb.command().addAll(pythonArgs);
            pb.redirectErrorStream(true);

            log.info("Exécution de la commande: {}", String.join(" ", pb.command()));
            Process process = pb.start();

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append("\n");
                    log.debug("[Python] {}", line);
                }
            }

            boolean finished = process.waitFor(CLEANING_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Python cleaning timed out after " + CLEANING_TIMEOUT_MINUTES + " minutes");
            }

            int exitCode = process.exitValue();
            String result = outputBuilder.toString().trim();

            if (exitCode != 0) {
                log.error("Python process failed with code {}: {}", exitCode, result);
                throw new RuntimeException("Python cleaning failed with code " + exitCode);
            }

            JsonNode root = extractJsonFromOutput(result);
            if (root == null || root.has("error")) {
                throw new RuntimeException("Python cleaning error: " +
                        (root != null ? root.get("error").asText() : "No JSON output"));
            }

            // 6. Retrieve cleaned Parquet output from container
            String cleanedFilename = "cleaned_" + datasetUuid + ".parquet";
            containerOutputFile = "/tmp/fraud-work/output/" + cleanedFilename;

            cleanedFile = Files.createTempFile("cleaned_", ".parquet");
            execDockerCopyFromContainer(containerOutputFile, cleanedFile);
            log.info("Fichier nettoyé récupéré: {}", cleanedFile);

            // 7. Upload cleaned Parquet to MinIO
            String minioKey = "data/cleaned/transactions/" + datasetUuid + "/" + cleanedFilename;
            try (InputStream cleanedInputStream = Files.newInputStream(cleanedFile)) {
                storageService.upload(minioKey, cleanedInputStream, Files.size(cleanedFile),
                        "application/octet-stream");
            }
            log.info("Fichier nettoyé uploadé vers MinIO: {}", minioKey);

            // 8. Parse stats and update dataset
            DatasetCleanResponse.CleaningStats cleaningStats = parseCleaningStats(root);
            List<String> columnsRemoved = parseColumnsRemoved(root);

            // Update filePath to the new Parquet file so future previews use it
            dataset.setFilePath(minioKey);
            dataset.setStatus(DatasetStatus.CLEANED);

            Map<String, Object> featureCols = dataset.getFeatureColumns();
            if (featureCols == null)
                featureCols = new HashMap<>();

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
            cleanup(tempFile, containerFileName);
            if (utf8File != null && !utf8File.equals(tempFile)) {
                try {
                    Files.deleteIfExists(utf8File);
                } catch (Exception e) {
                    log.warn("Nettoyage utf8 file failed: {}", e.getMessage());
                }
            }
            if (analysisFile != null)
                try {
                    Files.deleteIfExists(analysisFile);
                } catch (Exception ignored) {
                }
            if (containerAnalysisFile != null)
                try {
                    execDockerCommand("rm", "-f", containerAnalysisFile);
                } catch (Exception ignored) {
                }
            if (containerOutputFile != null)
                try {
                    execDockerCommand("rm", "-f", containerOutputFile);
                } catch (Exception ignored) {
                }
            if (cleanedFile != null)
                try {
                    Files.deleteIfExists(cleanedFile);
                } catch (Exception ignored) {
                }
        }
    }

    // ==================== UTF-8 CONVERSION (CSV only) ====================

    private Path convertToUtf8(Path inputFile) throws IOException {
        byte[] bytes = Files.readAllBytes(inputFile);

        try {
            String test = new String(bytes, StandardCharsets.UTF_8);
            byte[] reencoded = test.getBytes(StandardCharsets.UTF_8);
            if (Arrays.equals(reencoded, bytes)) {
                return null; // Already UTF-8
            }
        } catch (Exception e) {
            // Fall through to conversion
        }

        Charset[] charsets = {
                StandardCharsets.ISO_8859_1,
                Charset.forName("windows-1252"),
                Charset.forName("CP850"),
                Charset.forName("IBM437")
        };

        for (Charset charset : charsets) {
            try {
                String content = new String(bytes, charset);
                if (!content.isEmpty()) {
                    Path outputFile = Files.createTempFile("dataset_utf8_", ".csv");
                    try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                        writer.write(content);
                    }
                    log.info("Converti de {} vers UTF-8", charset.displayName());
                    return outputFile;
                }
            } catch (Exception e) {
                // Try next charset
            }
        }

        // Last resort: force ISO-8859-1
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        Path outputFile = Files.createTempFile("dataset_utf8_", ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        log.info("Conversion forcée de ISO-8859-1 vers UTF-8");
        return outputFile;
    }

    // ==================== DOCKER HELPERS ====================

    private String execDockerCommand(String... commands) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", onnxContainerName));
        cmd.addAll(Arrays.asList(commands));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("Docker command failed: " + output);
        return output;
    }

    private void execDockerCopy(Path source, String destination) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp", source.toAbsolutePath().toString(),
                onnxContainerName + ":" + destination);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("Docker copy failed: " + output);
    }

    private void execDockerCopyFromContainer(String containerPath, Path localPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp", onnxContainerName + ":" + containerPath,
                localPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("Docker copy from container failed: " + output);
    }

    // ==================== PARSING HELPERS ====================

    private DatasetCleanResponse.CleaningStats parseCleaningStats(JsonNode root) {
        JsonNode statsNode = root.get("cleaning_stats");
        if (statsNode == null)
            return DatasetCleanResponse.CleaningStats.builder().build();
        return DatasetCleanResponse.CleaningStats.builder()
                .originalRows(statsNode.path("original_rows").asLong(0))
                .cleanedRows(statsNode.path("cleaned_rows").asLong(0))
                .duplicatesRemoved(statsNode.path("duplicates_removed").asLong(0))
                .nullRowsRemoved(statsNode.path("null_rows_removed").asLong(0))
                .reductionPercent(statsNode.path("reduction_percent").asDouble(0.0))
                .fraudCount(statsNode.path("fraud_count_after").asLong(0))
                .fraudRate(statsNode.path("fraud_rate").asDouble(0.0))
                .build();
    }

    private List<String> parseColumnsRemoved(JsonNode root) {
        List<String> columnsRemoved = new ArrayList<>();
        if (root.has("cleaning_stats") && root.get("cleaning_stats").has("columns_removed")) {
            try {
                columnsRemoved = objectMapper.convertValue(
                        root.get("cleaning_stats").get("columns_removed"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                log.warn("Erreur parsing colonnes supprimées: {}", e.getMessage());
            }
        }
        return columnsRemoved;
    }

    private JsonNode extractJsonFromOutput(String output) {
        try {
            return objectMapper.readTree(output);
        } catch (Exception e) {
            /* fall through */ }
        int jsonStart = output.indexOf('{');
        int jsonEnd = output.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                return objectMapper.readTree(output.substring(jsonStart, jsonEnd + 1));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private DatasetAnalysisResponse parseAnalysisResponse(JsonNode root, Dataset dataset) {
        List<DatasetAnalysisResponse.ColumnInfo> columns = new ArrayList<>();
        JsonNode colsNode = root.get("columns");
        if (colsNode != null && colsNode.isArray()) {
            for (JsonNode colNode : colsNode) {
                columns.add(DatasetAnalysisResponse.ColumnInfo.builder()
                        .name(colNode.get("name").asText())
                        .type(colNode.get("type").asText())
                        .uniqueCount(colNode.get("uniqueCount").asInt())
                        .nullRatio(colNode.get("nullRatio").asDouble())
                        .stats(objectMapper.convertValue(colNode.get("stats"), Map.class))
                        .isTarget(false)
                        .build());
            }
        }

        // Handle null status
        String statusString = dataset.getStatus() != null ? dataset.getStatus().toString() : null;

        return DatasetAnalysisResponse.builder()
                .datasetUuid(dataset.getDatasetUuid())
                .datasetName(dataset.getDatasetName())
                .status(statusString)
                .columns(columns)
                .statistics(objectMapper.convertValue(root.get("statistics"), Map.class))
                .suggestedFeatures(objectMapper.convertValue(root.get("suggestedFeatures"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)))
                .suggestedTarget(root.has("suggestedTarget") ? root.get("suggestedTarget").asText() : null)
                .sampleRows(root.has("sampleRows") ? root.get("sampleRows").asInt() : 0)
                .preview(objectMapper.convertValue(root.get("preview"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)))
                .build();
    }

    // ==================== CLEANUP ====================

    private void cleanup(Path tempFile, String containerFileName) {
        if (containerFileName != null) {
            try {
                execDockerCommand("rm", "-f", containerFileName);
            } catch (Exception e) {
                log.warn("Nettoyage conteneur échoué: {}", e.getMessage());
            }
        }
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                log.warn("Suppression fichier temporaire échouée: {}", e.getMessage());
            }
        }
    }
}