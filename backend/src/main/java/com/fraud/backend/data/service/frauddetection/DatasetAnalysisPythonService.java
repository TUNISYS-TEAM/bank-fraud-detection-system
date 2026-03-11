package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import com.fraud.backend.data.entity.frauddetection.Dataset;
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
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetAnalysisPythonService {

    private final DatasetRepository datasetRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

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
            log.info("Fichier temporaire créé: {}", tempFile);

            // 2. Créer le dossier /app/data dans le conteneur s'il n'existe pas
            ProcessBuilder mkdirPb = new ProcessBuilder(
                    "docker", "exec", "fraud-ml-pipeline", "mkdir", "-p", "/app/data"
            );
            mkdirPb.start().waitFor();

            // 3. Copier le fichier dans le conteneur ml-pipeline
            containerFileName = "/app/data/" + tempFile.getFileName().toString();
            ProcessBuilder copyPb = new ProcessBuilder(
                    "docker", "cp", tempFile.toAbsolutePath().toString(),
                    "fraud-ml-pipeline:" + containerFileName);
            
            log.info("Copie du fichier vers le conteneur: {}", containerFileName);
            Process copyProcess = copyPb.start();
            int copyExitCode = copyProcess.waitFor();
            
            if (copyExitCode != 0) {
                // Lire les erreurs de la copie
                String copyError = readStream(copyProcess.getErrorStream());
                throw new RuntimeException("Failed to copy file to ml-pipeline container: " + copyError);
            }

            // 4. Lancer l'analyse dans le conteneur ml-pipeline
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", "fraud-ml-pipeline",
                    "python", "-m", "ml_pipeline.cli.main",
                    "analyze-dataset",
                    "--dataset-path", containerFileName);
            
            pb.redirectErrorStream(true); // Pour capturer stderr et stdout ensemble
            log.info("Lancement de l'analyse Python...");
            Process process = pb.start();

            // 5. Lire toute la sortie (pas seulement la dernière ligne)
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

            // 7. Extraire le JSON de la sortie (au cas où il y aurait des logs)
            JsonNode root = extractJsonFromOutput(output);
            
            if (root == null) {
                throw new RuntimeException("No valid JSON found in Python output: " + output);
            }

            if (root.has("error")) {
                throw new RuntimeException("Python error: " + root.get("error").asText());
            }

            // 8. Convertir en DatasetAnalysisResponse
            DatasetAnalysisResponse response = parseResponse(root, dataset);

            // 9. Sauvegarder les résultats dans le dataset
            Map<String, Object> featureCols = new HashMap<>();
            featureCols.put("columns", response.getColumns());
            featureCols.put("suggestedFeatures", response.getSuggestedFeatures());
            featureCols.put("suggestedTarget", response.getSuggestedTarget());
            dataset.setFeatureColumns(featureCols);
            dataset.setStatistics(response.getStatistics());
            datasetRepository.save(dataset);

            log.info("Analyse terminée avec succès pour le dataset: {}", dataset.getDatasetCode());
            return response;

        } catch (Exception e) {
            log.error("Erreur lors de l'analyse du dataset", e);
            throw e;
        } finally {
            // 10. Nettoyer les fichiers temporaires
            if (containerFileName != null) {
                try {
                    ProcessBuilder rmPb = new ProcessBuilder(
                            "docker", "exec", "fraud-ml-pipeline", "rm", "-f", containerFileName);
                    rmPb.start().waitFor();
                } catch (Exception e) {
                    log.warn("Erreur lors du nettoyage du fichier dans le conteneur: {}", e.getMessage());
                }
            }
            
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("Erreur lors de la suppression du fichier temporaire: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Extrait un objet JSON valide d'une chaîne qui peut contenir des logs supplémentaires
     */
    private JsonNode extractJsonFromOutput(String output) {
        // Essayer de parser la chaîne entière d'abord
        try {
            return objectMapper.readTree(output);
        } catch (Exception e) {
            log.debug("La sortie entière n'est pas un JSON valide, tentative d'extraction...");
        }

        // Chercher le premier '{' et le dernier '}'
        int jsonStart = output.indexOf('{');
        int jsonEnd = output.lastIndexOf('}');
        
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String jsonPart = output.substring(jsonStart, jsonEnd + 1);
            try {
                return objectMapper.readTree(jsonPart);
            } catch (Exception e2) {
                log.debug("Extraction JSON échouée: {}", e2.getMessage());
            }
        }

        // Chercher un pattern de JSON avec des crochets (pour les tableaux)
        int arrayStart = output.indexOf('[');
        int arrayEnd = output.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            String arrayPart = output.substring(arrayStart, arrayEnd + 1);
            try {
                return objectMapper.readTree(arrayPart);
            } catch (Exception e2) {
                log.debug("Extraction de tableau JSON échouée: {}", e2.getMessage());
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

    private DatasetAnalysisResponse parseResponse(JsonNode root, Dataset dataset) {
        // Construction manuelle à partir du JSON
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
        Map<String, Object> statistics = statsNode != null ? objectMapper.convertValue(statsNode, Map.class) : Map.of();

        List<String> suggestedFeatures = objectMapper.convertValue(root.get("suggestedFeatures"), 
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        
        String suggestedTarget = root.has("suggestedTarget") ? root.get("suggestedTarget").asText() : null;

        // Marquer la colonne cible
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
}