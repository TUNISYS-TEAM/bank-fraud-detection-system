package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetCleanResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetPreviewResponse;
import com.fraud.backend.api.exception.InvalidDatasetColumnsException;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.DatasetStatus;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.service.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasetRepository;
    private final StorageService storageService;
    private final DatasetAnalysisPythonService datasetAnalysisPythonService;
    private final ObjectMapper objectMapper;

    private static final Map<String, List<String>> REQUIRED_COLUMNS_WITH_ALIASES = Map.of(
            "account_id", List.of("account_id", "Account_Id", "id_compte", "compte", "account", "accountid"),
            "tx_time", List.of("tx_time", "TX_Time", "date", "transaction_time", "time", "timestamp"),
            "tx_amount", List.of("tx_amount", "TX_Amount", "montant", "amount", "valeur", "value"),
            "customer_postal_code",
            List.of("customer_postal_code", "Customer_Postal_Code", "code_postal", "postal_code", "zip_code", "zip"),
            "channel", List.of("channel", "Channel", "canal", "type_transaction"),
            "category", List.of("category", "Category", "catégorie", "type"));

    // ==================== PUBLIC CRUD ====================

    public Page<Dataset> list(String q, DatasetStatus status, Pageable pageable) {
        if (status != null) {
            return datasetRepository.findByStatus(status, pageable);
        }
        if (q != null && !q.isBlank()) {
            return datasetRepository.findByDatasetNameOrCode(q.trim(), pageable);
        }
        return datasetRepository.findAll(pageable);
    }

    public Dataset get(UUID uuid) {
        return datasetRepository.findByDatasetUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + uuid));
    }

    @Transactional
    public Dataset uploadTransactionsCsv(String datasetName, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException("datasetName is required");
        }

        validateCsvColumns(file);

        Dataset ds = Dataset.builder()
                .datasetName(datasetName.trim())
                .datasetCode(generateCode())
                .status(DatasetStatus.INACTIVE)
                .build();

        ds = datasetRepository.save(ds);

        String key = "data/raw/transactions/" + ds.getDatasetUuid() + "/original.csv";

        long rowCount = countCsvRows(file);
        BigDecimal sizeMb = bytesToMb(file.getSize());

        storageService.upload(
                key,
                file.getInputStream(),
                file.getSize(),
                file.getContentType() != null ? file.getContentType() : "text/csv");

        ds.updateFileStats(key, sizeMb, rowCount);
        return datasetRepository.save(ds);
    }

    @Transactional
    public void activate(UUID uuid) {
        Dataset target = get(uuid);
        if (target.getStatus() == DatasetStatus.ACTIVE) {
            return;
        }
        target.activate();
        datasetRepository.save(target);
    }

    @Transactional
    public void delete(UUID uuid) {
        Dataset ds = get(uuid);
        if (ds.getFilePath() != null && !ds.getFilePath().isBlank()) {
            storageService.delete(ds.getFilePath());
        }
        datasetRepository.delete(ds);
    }

    @Transactional
    public Dataset update(UUID uuid, DatasetUpdateRequest request) {
        Dataset ds = get(uuid);

        if (request.getDatasetName() != null && !request.getDatasetName().isBlank()) {
            ds.setDatasetName(request.getDatasetName().trim());
        }

        if (request.getStatus() != null) {
            if (request.getStatus() == DatasetStatus.ACTIVE) {
                activate(uuid);
            } else {
                ds.setStatus(DatasetStatus.INACTIVE);
            }
        }

        return datasetRepository.save(ds);
    }

    // ==================== ANALYSE ====================

    @Transactional
    public DatasetAnalysisResponse analyzeDataset(UUID datasetUuid) throws Exception {
        return datasetAnalysisPythonService.analyzeDataset(datasetUuid);
    }

    public DatasetAnalysisResponse getAnalysis(UUID datasetUuid) {
        Dataset dataset = get(datasetUuid);
        return buildAnalysisResponseFromDataset(dataset);
    }

    @SuppressWarnings("unchecked")
    private DatasetAnalysisResponse buildAnalysisResponseFromDataset(Dataset dataset) {
        if (dataset.getFeatureColumns() == null || dataset.getStatistics() == null) {
            return DatasetAnalysisResponse.builder()
                    .datasetUuid(dataset.getDatasetUuid())
                    .datasetName(dataset.getDatasetName())
                    .status(dataset.getStatus().toString())
                    .build();
        }

        List<Map<String, Object>> rawColumns = (List<Map<String, Object>>) dataset.getFeatureColumns().get("columns");

        List<DatasetAnalysisResponse.ColumnInfo> columnInfos = new ArrayList<>();
        if (rawColumns != null) {
            String suggestedTarget = (String) dataset.getFeatureColumns().get("suggestedTarget");
            for (Map<String, Object> col : rawColumns) {
                columnInfos.add(DatasetAnalysisResponse.ColumnInfo.builder()
                        .name((String) col.get("name"))
                        .type((String) col.get("type"))
                        .uniqueCount((Integer) col.get("uniqueCount"))
                        .nullRatio((Double) col.get("nullRatio"))
                        .stats((Map<String, Object>) col.get("stats"))
                        .isTarget(col.get("name").equals(suggestedTarget))
                        .build());
            }
        }

        return DatasetAnalysisResponse.builder()
                .datasetUuid(dataset.getDatasetUuid())
                .datasetName(dataset.getDatasetName())
                .status(dataset.getStatus().toString())
                .columns(columnInfos)
                .statistics(dataset.getStatistics())
                .suggestedFeatures((List<String>) dataset.getFeatureColumns().get("suggestedFeatures"))
                .suggestedTarget((String) dataset.getFeatureColumns().get("suggestedTarget"))
                .build();
    }

    // ==================== NETTOYAGE ====================

    @Transactional
    public DatasetCleanResponse cleanDataset(UUID datasetUuid) throws Exception {
        log.info("Démarrage du nettoyage pour dataset: {}", datasetUuid);
        return datasetAnalysisPythonService.cleanDataset(datasetUuid);
    }

    public DatasetCleanResponse getCleaningStatus(UUID datasetUuid) {
        Dataset dataset = get(datasetUuid);
        return DatasetCleanResponse.builder()
                .datasetUuid(dataset.getDatasetUuid())
                .datasetName(dataset.getDatasetName())
                .status(DatasetCleanResponse.CleaningStatus.COMPLETED)
                .build();
    }

    // ==================== PREVIEW ====================

    @Transactional(readOnly = true)
    public DatasetPreviewResponse preview(UUID uuid, int limit) throws IOException {

        Dataset ds = get(uuid);

        if (ds.getFilePath() == null || ds.getFilePath().isBlank()) {
            throw new IllegalStateException("Dataset has no file_path (not uploaded?)");
        }

        int safeLimit = Math.max(1, Math.min(limit, 50));
        String filePath = ds.getFilePath(); // keep original case for actual download

        log.info("Preview requested for dataset={} filePath={}", uuid, filePath);

        try {
            if (filePath.toLowerCase().endsWith(".parquet")) {
                log.info("Routing to Parquet preview for dataset={}", uuid);
                return previewParquet(ds, safeLimit);
            } else {
                log.info("Routing to CSV preview for dataset={}", uuid);
                return previewCsv(ds, safeLimit);
            }
        } catch (Exception e) {
            log.error("Preview failed for dataset={} filePath={} cause={}", uuid, filePath, e.getMessage(), e);
            throw e;
        }
    }

    // ── CSV ──────────────────────────────────────────────────────────────────────

    private DatasetPreviewResponse previewCsv(Dataset ds, int safeLimit) throws IOException {

        try (InputStream in = storageService.download(ds.getFilePath());
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .build()
                        .parse(reader)) {

            List<String> columns = new ArrayList<>(parser.getHeaderMap().keySet());
            List<Map<String, String>> rows = new ArrayList<>();
            int count = 0;

            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String col : columns) {
                    row.put(col, record.isMapped(col) ? record.get(col) : null);
                }
                rows.add(row);
                if (++count >= safeLimit)
                    break;
            }

            return DatasetPreviewResponse.builder()
                    .datasetUuid(ds.getDatasetUuid())
                    .datasetName(ds.getDatasetName())
                    .columns(columns)
                    .rows(rows)
                    .returnedRows(count)
                    .limit(safeLimit)
                    .build();
        }
    }

    // ── Parquet ──────────────────────────────────────────────────────────────────

    private DatasetPreviewResponse previewParquet(Dataset ds, int safeLimit) throws IOException {
        Path tempFile = Files.createTempFile("parquet-preview-", ".parquet");

        try {
            // Télécharger le fichier Parquet
            try (InputStream in = storageService.download(ds.getFilePath())) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Parquet temp file written: {} ({} bytes)", tempFile, Files.size(tempFile));

            // Utiliser Hadoop Path AVEC configuration
            org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(tempFile.toAbsolutePath().toString());

            // Configuration Hadoop minimale
            org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
            conf.set("fs.defaultFS", "file:///");
            conf.setBoolean("fs.file.impl.disable.cache", true);

            // Désactiver les warnings
            conf.set("hadoop.security.authentication", "simple");
            System.setProperty("hadoop.home.dir", System.getProperty("java.io.tmpdir"));

            // Construire le reader AVEC configuration
            try (ParquetReader<GenericRecord> reader = AvroParquetReader
                    .<GenericRecord>builder(hadoopPath)
                    .withConf(conf)
                    .build()) {

                List<String> columns = null;
                List<Map<String, String>> rows = new ArrayList<>();
                int count = 0;

                GenericRecord record;
                while ((record = reader.read()) != null && count < safeLimit) {

                    // Déterminer les colonnes à partir du premier enregistrement
                    if (columns == null && record != null) {
                        columns = record.getSchema().getFields()
                                .stream()
                                .map(org.apache.avro.Schema.Field::name)
                                .collect(Collectors.toList());
                        log.info("Parquet columns: {}", columns);
                    }

                    if (columns != null) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (String col : columns) {
                            Object val = record.get(col);
                            row.put(col, val != null ? val.toString() : null);
                        }
                        rows.add(row);
                        count++;
                    }
                }

                return DatasetPreviewResponse.builder()
                        .datasetUuid(ds.getDatasetUuid())
                        .datasetName(ds.getDatasetName())
                        .columns(columns != null ? columns : Collections.emptyList())
                        .rows(rows)
                        .returnedRows(count)
                        .limit(safeLimit)
                        .build();
            }

        } catch (Exception e) {
            log.error("Error reading Parquet file: {}", e.getMessage(), e);
            throw new IOException("Erreur lors de la lecture du fichier Parquet: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception e) {
                log.warn("Could not delete temp file: {}", tempFile);
            }
        }
    }

    // ==================== DOWNLOAD ====================

    @Transactional(readOnly = true)
    public InputStream download(UUID uuid) {
        Dataset ds = get(uuid);
        if (ds.getFilePath() == null || ds.getFilePath().isBlank()) {
            throw new IllegalStateException("Dataset has no file_path");
        }
        return storageService.download(ds.getFilePath());
    }

    // ==================== STATS ====================

    public long count() {
        return datasetRepository.count();
    }

    public BigDecimal getTotalSize() {
        return datasetRepository.sumFileSizeMb();
    }

    // ==================== PRIVATE HELPERS ====================

    private long countCsvRows(MultipartFile file) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            long lines = br.lines().count();
            return Math.max(0, lines - 1); // subtract header
        }
    }

    private BigDecimal bytesToMb(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BigDecimal.valueOf(1024.0 * 1024.0), 2, RoundingMode.HALF_UP);
    }

    private String generateCode() {
        return "DS-" + System.currentTimeMillis();
    }

    private void validateCsvColumns(MultipartFile file) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            br.mark(1024 * 1024);

            String firstLine = br.readLine();
            if (firstLine == null || firstLine.isBlank()) {
                throw new InvalidDatasetColumnsException("Le fichier est vide ou illisible.");
            }

            char delimiter = ',';
            String lineForDetection = firstLine.replace("\uFEFF", "");
            if (lineForDetection.contains(";") && !lineForDetection.contains(",")) {
                delimiter = ';';
            } else if (lineForDetection.contains(";") && lineForDetection.contains(",")) {
                long commas = lineForDetection.chars().filter(ch -> ch == ',').count();
                long semicolons = lineForDetection.chars().filter(ch -> ch == ';').count();
                if (semicolons > commas) {
                    delimiter = ';';
                }
            }

            br.reset();

            CSVParser parser = CSVFormat.DEFAULT
                    .builder()
                    .setDelimiter(delimiter)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .setIgnoreHeaderCase(true)
                    .build()
                    .parse(br);

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                throw new InvalidDatasetColumnsException(
                        "Impossible de lire les colonnes (Délimiteur : '" + delimiter + "').");
            }

            Set<String> headers = headerMap.keySet().stream()
                    .map(h -> h.replaceAll("[\\p{C}\\p{Z}]", "").trim())
                    .filter(h -> !h.isEmpty())
                    .collect(Collectors.toSet());

            List<String> missingColumns = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry : REQUIRED_COLUMNS_WITH_ALIASES.entrySet()) {
                String baseColumn = entry.getKey();
                List<String> aliases = entry.getValue();

                boolean found = aliases.stream()
                        .anyMatch(alias -> headers.stream()
                                .anyMatch(h -> h.equalsIgnoreCase(alias.trim())));

                if (!found) {
                    missingColumns.add(baseColumn);
                }
            }

            if (!missingColumns.isEmpty()) {
                String errorMsg = "Colonnes manquantes : " + String.join(", ", missingColumns) + ". "
                        + "Colonnes détectées : [" + String.join(", ", headers) + "]. "
                        + "Délimiteur : '" + delimiter + "'.";
                throw new InvalidDatasetColumnsException(errorMsg);
            }
        }
    }
}