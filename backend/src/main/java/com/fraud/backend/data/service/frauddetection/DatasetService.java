package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetCleanResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetPreviewResponse;
import com.fraud.backend.api.exception.InvalidDatasetColumnsException;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.AccountType;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.repository.corebanking.ClientRepository;
import com.fraud.backend.data.repository.corebanking.AccountRepository;
import com.fraud.backend.data.service.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.avro.generic.GenericRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasetRepository;
    private final StorageService storageService;
    private final DatasetAnalysisPythonService datasetAnalysisPythonService;
    private final ClientRepository clientRepository;
    private final AccountRepository accountRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final List<DatasetType> ADMIN_KPI_TYPES = List.of(
            DatasetType.CLIENTS,
            DatasetType.ACCOUNTS,
            DatasetType.TRANSACTIONS_VAL);

    private static final int BATCH_SIZE = 1000;

    private static final Map<DatasetType, Map<String, List<String>>> REQUIRED_COLUMNS_BY_TYPE = Map.of(
            DatasetType.TRANSACTIONS_TRAIN, Map.of(
                    "account_id", List.of("account_id", "Account_Id", "id_compte", "compte", "account", "accountid"),
                    "tx_time", List.of("tx_time", "TX_Time", "date", "transaction_time", "time", "timestamp"),
                    "tx_amount", List.of("tx_amount", "TX_Amount", "montant", "amount", "valeur", "value"),
                    "customer_postal_code",
                    List.of("customer_postal_code", "Customer_Postal_Code", "code_postal", "postal_code", "zip_code",
                            "zip"),
                    "channel", List.of("channel", "Channel", "canal", "type_transaction"),
                    "category", List.of("category", "Category", "catégorie", "type"),
                    "target", List.of("target", "is_fraud", "fraud", "label")),
            DatasetType.TRANSACTIONS_VAL, Map.ofEntries(
                    Map.entry("Account_Id", List.of("Account_Id", "account_number", "account_id", "id_compte")),
                    Map.entry("TX_Time", List.of("TX_Time", "tx_time", "transaction_time", "time", "date")),
                    Map.entry("TX_Amount", List.of("TX_Amount", "tx_amount", "amount", "montant")),
                    Map.entry("Customer_Postal_Code",
                            List.of("Customer_Postal_Code", "customer_postal_code", "postal_code_client",
                                    "code_postal_client")),
                    Map.entry("Merchant_Postal_Code",
                            List.of("Merchant_Postal_Code", "merchant_postal_code", "postal_code_merchant",
                                    "code_postal_marchand")),
                    Map.entry("channel", List.of("channel", "Channel", "canal")),
                    Map.entry("category", List.of("category", "Category", "merchant_category", "type")),
                    Map.entry("Device_Id", List.of("Device_Id", "device_id", "id_appareil")),
                    Map.entry("ip_address", List.of("ip_adress", "ip_address", "ip", "adresse_ip")),
                    Map.entry("currency", List.of("currency", "devise", "monnaie"))),
            DatasetType.CLIENTS, Map.of(
                    "Client_Id", List.of("Client_Id", "id_client", "client_id", "id"),
                    "Full_Name", List.of("Full_Name", "full_name", "nom_complet", "name", "nom"),
                    "Date_of_Birth", List.of("Date_of_Birth", "date_of_birth", "dob", "date_naissance"),
                    "National_Id", List.of("National_Id", "national_id", "cin", "nic", "id_national"),
                    "Created_At", List.of("Created_At", "created_at", "date_creation")),
            DatasetType.ACCOUNTS, Map.of(
                    "Account_Id", List.of("Account_Id", "account_id", "numero_compte", "account_number", "id_compte"),
                    "Client_Id", List.of("Client_Id", "client_id", "id_client"),
                    "Account_Type", List.of("Account_Type", "account_type", "type_compte", "type"),
                    "Balance", List.of("Balance", "balance", "solde"),
                    "Status", List.of("Status", "status", "etat", "is_active"),
                    "Created_At", List.of("Created_At", "created_at", "date_ouverture", "opened_date")));

    // ==================== PUBLIC CRUD ====================

    public Page<Dataset> list(String q, DatasetStatus status, DatasetType type, Pageable pageable) {
        if (status != null && type != null) {
            return datasetRepository.findByStatusAndType(status, type, pageable);
        }
        if (status != null) {
            return datasetRepository.findByStatus(status, pageable);
        }
        if (type != null) {
            return datasetRepository.findByType(type, pageable);
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
    public Dataset uploadDataset(String datasetName, DatasetType type, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException("datasetName is required");
        }

        // Validation des colonnes selon le type
        validateCsvColumns(file, type);

        // Gérer la contrainte "un seul actif/existant" pour CLIENTS et ACCOUNTS
        if (type == DatasetType.CLIENTS || type == DatasetType.ACCOUNTS) {
            List<Dataset> existing = datasetRepository.findByType(type, Pageable.unpaged()).getContent();
            for (Dataset e : existing) {
                delete(e.getDatasetUuid());
            }
        }

        // Créer dataset
        Dataset ds = Dataset.builder()
                .datasetName(datasetName.trim())
                .datasetCode(generateCode())
                .status(DatasetStatus.ACTIVE)
                .type(type)
                .build();

        ds = datasetRepository.save(ds);

        // Key Data Lake
        String folder = type.toString().toLowerCase();
        String key = "data/raw/" + folder + "/" + ds.getDatasetUuid() + "/original.csv";

        // Stats
        long rowCount = countCsvRows(file);
        BigDecimal sizeMb = bytesToMb(file.getSize());

        // Upload MinIO
        storageService.upload(
                key,
                file.getInputStream(),
                file.getSize(),
                file.getContentType() != null ? file.getContentType() : "text/csv");

        // Update metadata + save
        ds.updateFileStats(key, sizeMb, rowCount);
        ds = datasetRepository.save(ds);

        // Si CLIENTS ou ACCOUNTS, insertion immédiate en base
        if (type == DatasetType.CLIENTS) {
            importClientsFromCsv(file);
        } else if (type == DatasetType.ACCOUNTS) {
            importAccountsFromCsv(file);
        } else if (type == DatasetType.TRANSACTIONS_VAL) {
            importTransactionsValFromCsv(file);
        }

        return ds;
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
        try {
            if (ds.getType() == DatasetType.CLIENTS) {
                // Ordre : enfants → parents
                jdbcTemplate.execute("DELETE FROM fraud_alert");
                jdbcTemplate.execute("DELETE FROM client_behavior_history");
                jdbcTemplate.execute("DELETE FROM client_behavior");
                jdbcTemplate.execute("DELETE FROM transaction");
                jdbcTemplate.execute("DELETE FROM account");
                jdbcTemplate.execute("DELETE FROM client");
            } else if (ds.getType() == DatasetType.ACCOUNTS) {
                jdbcTemplate.execute("DELETE FROM fraud_alert");
                jdbcTemplate.execute("DELETE FROM client_behavior_history");
                jdbcTemplate.execute("DELETE FROM client_behavior");
                jdbcTemplate.execute("DELETE FROM transaction");
                jdbcTemplate.execute("DELETE FROM account");
            } else if (ds.getType() == DatasetType.TRANSACTIONS_VAL) {
                jdbcTemplate.execute("DELETE FROM fraud_alert");
                jdbcTemplate.execute("DELETE FROM client_behavior_history");
                jdbcTemplate.execute("DELETE FROM transaction");
            }

            // Supprimer le fichier du datalake (MinIO)
            if (ds.getFilePath() != null && !ds.getFilePath().isBlank()) {
                storageService.delete(ds.getFilePath());
            }
            // Supprimer l'entrée dataset
            datasetRepository.delete(ds);
            datasetRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Échec de la suppression du dataset {} ({}) : {}", ds.getDatasetName(), uuid, e.getMessage());
            throw new IllegalArgumentException(
                    "Impossible de supprimer ce dataset car des données sont référencées par d'autres modules.");
        }
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
        String filePath = ds.getFilePath();

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

    // ── CSV Preview ───────────────────────────────────────────────────────────

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

    // ── Parquet Preview with Avro (works on Windows) ─────────────────────────

    private DatasetPreviewResponse previewParquet(Dataset ds, int safeLimit) throws IOException {
        Path tempFile = Files.createTempFile("parquet-preview-", ".parquet");

        try {
            // Télécharger le fichier Parquet
            try (InputStream in = storageService.download(ds.getFilePath())) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Parquet temp file written: {} ({} bytes)", tempFile, Files.size(tempFile));

            // Configuration Hadoop minimale pour Windows
            org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(tempFile.toAbsolutePath().toString());

            org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
            conf.set("fs.defaultFS", "file:///");
            conf.setBoolean("fs.file.impl.disable.cache", true);
            conf.set("hadoop.security.authentication", "simple");

            // Important: Définir hadoop.home.dir au répertoire temporaire pour éviter la
            // recherche de winutils.exe
            String tempDir = System.getProperty("java.io.tmpdir");
            System.setProperty("hadoop.home.dir", tempDir);
            conf.set("hadoop.home.dir", tempDir);

            // Utiliser AvroParquetReader qui fonctionne bien avec GenericRecord
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

    public long count(List<DatasetType> types) {
        List<DatasetType> targetTypes = (types != null && !types.isEmpty()) ? types : ADMIN_KPI_TYPES;
        return datasetRepository.countByTypeIn(targetTypes);
    }

    public BigDecimal getTotalSize() {
        return datasetRepository.sumFileSizeMb();
    }

    public BigDecimal getTotalSize(List<DatasetType> types) {
        List<DatasetType> targetTypes = (types != null && !types.isEmpty()) ? types : ADMIN_KPI_TYPES;
        return datasetRepository.sumFileSizeMbByTypeIn(targetTypes);
    }

    // ==================== PRIVATE HELPERS ====================

    private long countCsvRows(MultipartFile file) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            long lines = br.lines().count();
            return Math.max(0, lines - 1);
        }
    }

    private BigDecimal bytesToMb(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BigDecimal.valueOf(1024.0 * 1024.0), 2, RoundingMode.HALF_UP);
    }

    private String generateCode() {
        return "DS-" + System.currentTimeMillis();
    }

    private void validateCsvColumns(MultipartFile file, DatasetType type) throws IOException {
        getColumnMapping(file, type);
    }

    private Map<String, String> getColumnMapping(MultipartFile file, DatasetType type) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            br.mark(1024 * 1024);
            String firstLine = br.readLine();
            if (firstLine == null || firstLine.isBlank()) {
                throw new InvalidDatasetColumnsException("Le fichier est vide ou illisible.");
            }
            char delimiter = detectDelimiter(firstLine);
            br.reset();

            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimiter)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .setIgnoreHeaderCase(true)
                    .build()
                    .parse(br);

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                throw new InvalidDatasetColumnsException("Impossible de lire les colonnes.");
            }

            Set<String> detectedHeaders = headerMap.keySet().stream()
                    .map(h -> h.replaceAll("[\\p{C}\\p{Z}]", "").trim())
                    .filter(h -> !h.isEmpty())
                    .collect(Collectors.toSet());

            Map<String, List<String>> requiredMap = REQUIRED_COLUMNS_BY_TYPE.get(type);
            Map<String, String> resultMapping = new HashMap<>();
            List<String> missingColumns = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry : requiredMap.entrySet()) {
                String baseColumn = entry.getKey();
                List<String> aliases = entry.getValue();
                String foundHeader = null;

                for (String alias : aliases) {
                    foundHeader = detectedHeaders.stream()
                            .filter(h -> h.equalsIgnoreCase(alias.trim()))
                            .findFirst()
                            .orElse(null);
                    if (foundHeader != null)
                        break;
                }

                if (foundHeader == null) {
                    missingColumns.add(baseColumn);
                } else {
                    resultMapping.put(baseColumn, foundHeader);
                }
            }

            if (!missingColumns.isEmpty()) {
                throw new InvalidDatasetColumnsException(
                        "Colonnes manquantes pour le type " + type + " : " + String.join(", ", missingColumns) +
                                ". Colonnes détectées : [" + String.join(", ", detectedHeaders) + "]");
            }

            return resultMapping;
        }
    }

    private char detectDelimiter(String line) {
        char delimiter = ',';
        String d = line.replace("\uFEFF", "");
        if (d.contains(";") && !d.contains(",")) {
            delimiter = ';';
        } else if (d.contains(";") && d.contains(",")) {
            long commas = d.chars().filter(ch -> ch == ',').count();
            long semicolons = d.chars().filter(ch -> ch == ';').count();
            if (semicolons > commas) {
                delimiter = ';';
            }
        }
        return delimiter;
    }

    private char getDelimiterForFile(MultipartFile file) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String firstLine = br.readLine();
            return (firstLine != null) ? detectDelimiter(firstLine) : ',';
        }
    }

    // ==================== DATABASE IMPORT METHODS ====================

    private void importClientsFromCsv(MultipartFile file) throws IOException {
        accountRepository.deleteAllInBatch();
        clientRepository.deleteAllInBatch();

        Map<String, String> columnMapping = getColumnMapping(file, DatasetType.CLIENTS);
        char delimiter = getDelimiterForFile(file);

        String sql = "INSERT INTO client (client_id, first_name, last_name, date_of_birth, national_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setDelimiter(delimiter)
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .setIgnoreHeaderCase(true)
                        .build()
                        .parse(br)) {

            List<Object[]> batch = new ArrayList<>();
            int lineNumber = 0;
            Timestamp now = new Timestamp(System.currentTimeMillis());

            for (CSVRecord record : parser) {
                lineNumber++;
                try {
                    String fullName = record.get(columnMapping.get("Full_Name"));
                    String firstName = "";
                    String lastName = "";
                    if (fullName != null && !fullName.isBlank()) {
                        String[] parts = fullName.split(" ", 2);
                        firstName = parts[0];
                        if (parts.length > 1) {
                            lastName = parts[1];
                        }
                    }

                    Long clientId = parseLongSafe(record.get(columnMapping.get("Client_Id")));
                    LocalDate dob = parseLocalDateSafe(record.get(columnMapping.get("Date_of_Birth")));
                    String nationalId = record.get(columnMapping.get("National_Id"));
                    LocalDateTime createdAt = parseLocalDateTimeSafe(record.get(columnMapping.get("Created_At")));
                    Timestamp tsCreatedAt = (createdAt != null) ? Timestamp.valueOf(createdAt) : now;

                    batch.add(new Object[] {
                            clientId, firstName, lastName,
                            dob != null ? java.sql.Date.valueOf(dob) : null,
                            nationalId,
                            tsCreatedAt,
                            now
                    });

                    if (batch.size() >= BATCH_SIZE) {
                        jdbcTemplate.batchUpdate(sql, batch);
                        batch.clear();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Erreur à la ligne " + lineNumber + " (Client): " + e.getMessage());
                }
            }

            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
            log.info("Import clients terminé : {} lignes insérées via JDBC batch", lineNumber);
        }
    }

    private void importAccountsFromCsv(MultipartFile file) throws IOException {
        accountRepository.deleteAllInBatch();

        Map<String, String> columnMapping = getColumnMapping(file, DatasetType.ACCOUNTS);
        char delimiter = getDelimiterForFile(file);

        Set<Long> existingClientIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT client_id FROM client", Long.class));
        log.info("Pré-chargement de {} IDs clients terminé", existingClientIds.size());

        String sql = "INSERT INTO account (account_number, account_type, balance, currency, is_active, risk_level, client_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setDelimiter(delimiter)
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .setIgnoreHeaderCase(true)
                        .build()
                        .parse(br)) {

            List<Object[]> batch = new ArrayList<>();
            int lineNumber = 0;
            int skipped = 0;
            Timestamp now = new Timestamp(System.currentTimeMillis());

            for (CSVRecord record : parser) {
                lineNumber++;
                try {
                    Long clientNumId = parseLongSafe(record.get(columnMapping.get("Client_Id")));

                    if (clientNumId == null || !existingClientIds.contains(clientNumId)) {
                        if (skipped < 10) {
                            log.warn(
                                    "Ligne {}: Client non trouvé pour ID '{}'",
                                    lineNumber, record.get(columnMapping.get("Client_Id")));
                        }
                        skipped++;
                        continue;
                    }

                    String statusStr = record.get(columnMapping.get("Status")).toLowerCase();
                    boolean isActive = statusStr.equals("active") || statusStr.equals("true") || statusStr.equals("1")
                            || statusStr.equals("actif");

                    String accountNumberStr = record.get(columnMapping.get("Account_Id"));
                    AccountType accountType = parseAccountType(record.get(columnMapping.get("Account_Type")));
                    BigDecimal balance = parseBigDecimalSafe(record.get(columnMapping.get("Balance")));
                    LocalDateTime createdAt = parseLocalDateTimeSafe(record.get(columnMapping.get("Created_At")));
                    Timestamp tsCreatedAt = (createdAt != null) ? Timestamp.valueOf(createdAt) : now;

                    batch.add(new Object[] {
                            accountNumberStr != null ? accountNumberStr.trim() : null,
                            accountType.name(),
                            balance,
                            "TND",
                            isActive,
                            "LOW",
                            clientNumId,
                            tsCreatedAt,
                            now
                    });

                    if (batch.size() >= BATCH_SIZE) {
                        jdbcTemplate.batchUpdate(sql, batch);
                        batch.clear();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Erreur à la ligne " + lineNumber + " (Compte): " + e.getMessage());
                }
            }

            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
            log.info("Import accounts terminé : {} lignes traitées, {} insérées, {} ignorées",
                    lineNumber, lineNumber - skipped, skipped);
        }
    }

    private void importTransactionsValFromCsv(MultipartFile file) throws IOException {
        boolean triggerDisabled = false;
        try {
            // Disable trigger for performance during bulk insert
            jdbcTemplate.execute("ALTER TABLE transaction DISABLE TRIGGER after_transaction_insert;");
            triggerDisabled = true;

            Map<String, String> columnMapping = getColumnMapping(file, DatasetType.TRANSACTIONS_VAL);
            char delimiter = getDelimiterForFile(file);
            log.info("Début import TRANSACTIONS_VAL. Colonnes trouvées: {}. Délimiteur: {}", columnMapping, delimiter);

            // Get account mappings - account table uses 'id' as primary key
            // The CSV has Account_Id which maps to account.account_number, then we need the
            // account.id
            Map<String, Long> accountNumberToId = jdbcTemplate.query(
                    "SELECT account_number, id FROM account",
                    (rs, rowNum) -> Map.entry(rs.getString("account_number"), rs.getLong("id"))).stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            log.info("{} comptes chargés depuis la base (account_number -> id)", accountNumberToId.size());

            String sql = "INSERT INTO transaction (transaction_uuid, account_id, amount, currency, transaction_date, " +
                    "merchant_category, channel, status, postal_code_merchant, postal_code_client, device_id, " +
                    "ip_address, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
                    CSVParser parser = CSVFormat.DEFAULT.builder()
                            .setDelimiter(delimiter)
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setTrim(true)
                            .setIgnoreHeaderCase(true)
                            .build()
                            .parse(br)) {

                List<Object[]> batch = new ArrayList<>();
                Timestamp now = new Timestamp(System.currentTimeMillis());
                int count = 0;
                int skipped = 0;

                for (CSVRecord record : parser) {
                    try {
                        // Get the mapped column name for Account_Id from the CSV
                        String accountNumberHeader = columnMapping.get("Account_Id");
                        if (accountNumberHeader == null) {
                            log.error("Colonne Account_Id manquante dans le mapping!");
                            break;
                        }

                        String rawAccountNumber = record.get(accountNumberHeader);
                        if (rawAccountNumber == null || rawAccountNumber.isBlank()) {
                            skipped++;
                            continue;
                        }

                        // Clean the account number to match the format in the database
                        String accountNumber = cleanStringSafe(rawAccountNumber);

                        // Get the account.id from the account_number
                        Long accountId = accountNumberToId.get(accountNumber);
                        if (accountId == null) {
                            if (skipped < 5) {
                                log.warn("Compte non trouvé pour account_number: '{}' (nettoyé: '{}'). " +
                                        "Vérifiez que ce compte existe dans la table account",
                                        rawAccountNumber, accountNumber);
                            }
                            skipped++;
                            continue;
                        }

                        BigDecimal amount = parseBigDecimalSafe(
                                cleanStringSafe(record.get(columnMapping.get("TX_Amount"))));
                        LocalDateTime txTime = parseLocalDateTimeSafe(
                                cleanStringSafe(record.get(columnMapping.get("TX_Time"))));

                        String category = cleanStringSafe(record.get(columnMapping.get("category")));
                        String channel = parseChannelSafe(cleanStringSafe(record.get(columnMapping.get("channel"))));
                        String mPostal = cleanStringSafe(record.get(columnMapping.get("Merchant_Postal_Code")));
                        String cPostal = cleanStringSafe(record.get(columnMapping.get("Customer_Postal_Code")));
                        String deviceId = cleanStringSafe(record.get(columnMapping.get("Device_Id")));
                        String ipAddress = cleanStringSafe(record.get(columnMapping.get("ip_address")));
                        String cur = cleanStringSafe(record.get(columnMapping.get("currency")));
                        if (cur.isBlank())
                            cur = "TND";

                        batch.add(new Object[] {
                                UUID.randomUUID(), // transaction_uuid
                                accountId, // account_id (foreign key to account.id)
                                amount, // amount
                                cur, // currency
                                txTime != null ? Timestamp.valueOf(txTime) : now, // transaction_date
                                category, // merchant_category
                                channel, // channel
                                "COMPLETED", // status
                                mPostal, // postal_code_merchant
                                cPostal, // postal_code_client
                                deviceId, // device_id
                                ipAddress, // ip_address
                                now, // created_at
                                now // updated_at
                        });

                        if (batch.size() >= BATCH_SIZE) {
                            jdbcTemplate.batchUpdate(sql, batch);
                            batch.clear();
                        }
                        count++;
                    } catch (Exception e) {
                        log.error("Erreur ligne {}: {}", record.getRecordNumber(), e.getMessage());
                        skipped++;
                    }
                }

                if (!batch.isEmpty()) {
                    jdbcTemplate.batchUpdate(sql, batch);
                }
                log.info("Import terminé: {} transactions insérées, {} ignorées.", count, skipped);
            }
        } catch (Exception e) {
            log.error("Erreur fatale lors de l'import TRANSACTIONS_VAL: ", e);
            throw new IOException("Echec de l'importation: " + e.getMessage(), e);
        } finally {
            // Re-enable the trigger
            if (triggerDisabled) {
                try {
                    jdbcTemplate.execute("ALTER TABLE transaction ENABLE TRIGGER after_transaction_insert;");
                    log.info("Trigger after_transaction_insert réactivé");
                } catch (Exception e) {
                    log.warn("Erreur lors de la réactivation du trigger: {}", e.getMessage());
                }
            }
        }
    }

    // ==================== PARSING UTILITIES ====================

    private Long parseLongSafe(String value) {
        if (value == null || value.isBlank())
            return null;
        try {
            String cleaned = value.trim().replaceAll("[\u200B-\u200D\uFEFF]", "");
            if (cleaned.contains(".") || cleaned.toLowerCase().contains("e")) {
                return (long) Double.parseDouble(cleaned);
            }
            return Long.parseLong(cleaned.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            log.error("Erreur parsing Long pour la valeur '{}' : {}", value, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseBigDecimalSafe(String value) {
        if (value == null || value.isBlank())
            return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replace(",", ".").replaceAll("[^0-9.]", "").trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDate parseLocalDateSafe(String value) {
        if (value == null || value.isBlank())
            return LocalDate.now();
        try {
            if (value.contains("/")) {
                String[] parts = value.split("/");
                if (parts.length == 3) {
                    if (parts[2].length() == 4)
                        return LocalDate.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[0]));
                    else if (parts[0].length() == 4)
                        return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]));
                }
            }
            return LocalDate.parse(value);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private LocalDateTime parseLocalDateTimeSafe(String value) {
        if (value == null || value.isBlank())
            return LocalDateTime.now();
        try {
            String cleaned = value.trim().replace(" ", "T");
            return LocalDateTime.parse(cleaned);
        } catch (Exception e) {
            return parseLocalDateSafe(value).atStartOfDay();
        }
    }

    private AccountType parseAccountType(String value) {
        if (value == null)
            return AccountType.COURANT;
        try {
            return AccountType.valueOf(value.toUpperCase());
        } catch (Exception e) {
            if (value.toLowerCase().contains("courant"))
                return AccountType.COURANT;
            if (value.toLowerCase().contains("epargne"))
                return AccountType.EPARGNE;
            return AccountType.COURANT;
        }
    }

    private String cleanStringSafe(String value) {
        if (value == null || value.isBlank())
            return "";
        return value.trim().replaceAll("^['\"]+", "").replaceAll("['\"]+$", "").trim();
    }

    private String parseChannelSafe(String value) {
        if (value == null)
            return "ONLINE";
        String v = value.trim().toLowerCase().replace(" ", "_");
        if (v.contains("en_ligne") || v.contains("online") || v.contains("internet"))
            return "ONLINE";
        if (v.contains("gab") || v.contains("atms") || v.contains("pos"))
            return "POS";
        if (v.contains("mobile"))
            return "MOBILE";
        if (v.contains("virement") || v.contains("transfer"))
            return "VIREMENT";
        if (v.contains("cheque"))
            return "CHEQUE";
        return "ONLINE";
    }

    public Page<Dataset> listTrainingDatasets(String q, Pageable pageable) {
        List<DatasetStatus> statuses = List.of(DatasetStatus.ACTIVE, DatasetStatus.PROCESSED);

        if (q != null && !q.isBlank()) {
            return datasetRepository.findTrainingDatasetsBySearchTerm(
                    DatasetType.TRANSACTIONS_TRAIN,
                    statuses,
                    q.trim(),
                    pageable);
        }

        return datasetRepository.findByTypeAndStatusIn(
                DatasetType.TRANSACTIONS_TRAIN,
                statuses,
                pageable);
    }

    public Page<Dataset> listAllTrainingDatasets(String q, Pageable pageable) {
        if (q != null && !q.isBlank()) {
            // Search with term across all TRANSACTIONS_TRAIN datasets
            return datasetRepository.findByTypeAndSearchTerm(
                    DatasetType.TRANSACTIONS_TRAIN,
                    q.trim(),
                    pageable);
        }
        // Get all TRANSACTIONS_TRAIN datasets regardless of status
        return datasetRepository.findByType(DatasetType.TRANSACTIONS_TRAIN, pageable);
    }

    // ========================== EXTRACTION COMPORTEMENT CLIENT
    // =============================

    @Transactional
    public DatasetCleanResponse extractBehavior(UUID datasetUuid) throws Exception {
        Dataset sourceDataset = get(datasetUuid);
        String jobId = UUID.randomUUID().toString();

        // Vérifier que le dataset source est du bon type
        if (sourceDataset.getType() != DatasetType.TRANSACTIONS_TRAIN &&
                sourceDataset.getType() != DatasetType.TRANSACTIONS_VAL) {
            throw new IllegalArgumentException("Seuls les datasets de transactions peuvent être enrichis");
        }

        // Détection du format
        String storedPath = sourceDataset.getFilePath();
        boolean isParquet = storedPath != null && storedPath.toLowerCase().endsWith(".parquet");
        String extension = isParquet ? ".parquet" : ".csv";

        log.info("Extraction des features comportementales - dataset source={} format={}",
                datasetUuid, isParquet ? "parquet" : "csv");

        Path tempFile = null;
        Path utf8File = null;
        Path outputFile = null;
        String containerInput = null;
        String containerOutput = null;
        Dataset newDataset = null;

        try {
            // 1. Télécharger depuis MinIO
            tempFile = Files.createTempFile("behavior_input_", extension);
            try (InputStream in = storageService.download(storedPath);
                    OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }
            log.info("Fichier téléchargé: {}", tempFile);

            // 2. Conversion UTF-8 si CSV
            if (isParquet) {
                utf8File = tempFile;
            } else {
                utf8File = convertToUtf8(tempFile);
                if (utf8File == null)
                    utf8File = tempFile;
            }

            // 3. Copier dans le conteneur
            execDockerCommand("mkdir", "-p", "/app/data");
            containerInput = "/app/data/" + utf8File.getFileName().toString();
            execDockerCopy(utf8File, containerInput);
            log.info("Fichier copié dans le conteneur: {}", containerInput);

            // 4. Construire la commande Python
            List<String> cmd = new ArrayList<>();
            cmd.addAll(List.of(
                    "docker", "exec", "fraud-ml-pipeline",
                    "python", "-m", "ml_pipeline.cli.main",
                    "extract-behavior",
                    "--input", containerInput,
                    "--dataset-id", sourceDataset.getDatasetUuid().toString(),
                    "--job-id", jobId));

            log.info("Exécution de la commande: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
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

            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Extraction timeout après 30 minutes");
            }

            int exitCode = process.exitValue();
            String result = outputBuilder.toString().trim();
            log.info("Processus terminé avec code: {}", exitCode);

            if (exitCode != 0) {
                log.error("Python failed: {}", result);
                throw new RuntimeException("Extraction failed with code " + exitCode);
            }

            // 5. Extraire le JSON de sortie
            JsonNode root = extractJsonFromOutput(result);
            if (root == null) {
                throw new RuntimeException("Aucun JSON valide trouvé dans la sortie");
            }

            if (root.has("status") && "error".equals(root.get("status").asText())) {
                throw new RuntimeException("Erreur Python: " + root.get("error").asText());
            }

            // 6. Récupérer le chemin du fichier de sortie depuis le JSON
            String outputFilename = "enriched_" + sourceDataset.getDatasetUuid() + "_" + jobId + ".parquet";
            containerOutput = "/app/data/" + outputFilename;

            // Télécharger le fichier depuis le conteneur
            outputFile = Files.createTempFile("enriched_", ".parquet");
            execDockerCopyFromContainer(containerOutput, outputFile);
            log.info("Fichier enrichi récupéré: {}", outputFile);

            // 7. CRÉER UN NOUVEAU DATASET (copie enrichie)
            String newDatasetName = sourceDataset.getDatasetName() + " (enriched)";
            String newDatasetCode = generateCode();

            newDataset = Dataset.builder()
                    .datasetName(newDatasetName)
                    .datasetCode(newDatasetCode)
                    .status(DatasetStatus.ACTIVE)
                    .type(sourceDataset.getType())
                    .build();

            newDataset = datasetRepository.save(newDataset);

            // 8. Upload du fichier enrichi vers MinIO pour le NOUVEAU dataset
            String minioKey = "data/enriched/transactions/" + newDataset.getDatasetUuid() + "/" + outputFilename;
            try (InputStream is = Files.newInputStream(outputFile)) {
                storageService.upload(minioKey, is, Files.size(outputFile), "application/octet-stream");
            }
            log.info("Fichier enrichi uploadé vers MinIO: {}", minioKey);

            // 9. Mettre à jour les stats du nouveau dataset
            long rowCount = root.path("original_rows").asLong(0);
            BigDecimal fileSizeMb = bytesToMb(Files.size(outputFile));
            newDataset.updateFileStats(minioKey, fileSizeMb, rowCount);

            // 10. Ajouter les métadonnées d'enrichissement
            Map<String, Object> featureCols = newDataset.getFeatureColumns();
            if (featureCols == null) {
                featureCols = new HashMap<>();
            }

            Map<String, Object> enrichmentMetadata = new HashMap<>();
            enrichmentMetadata.put("sourceDatasetId", sourceDataset.getDatasetUuid().toString());
            enrichmentMetadata.put("sourceDatasetName", sourceDataset.getDatasetName());
            enrichmentMetadata.put("jobId", jobId);
            enrichmentMetadata.put("extractedAt", LocalDateTime.now().toString());

            JsonNode columnsAddedNode = root.get("columns_added");
            if (columnsAddedNode != null && columnsAddedNode.isArray()) {
                List<String> columnsAdded = new ArrayList<>();
                for (JsonNode col : columnsAddedNode) {
                    columnsAdded.add(col.asText());
                }
                enrichmentMetadata.put("columnsAdded", columnsAdded);
            }

            featureCols.put("enrichment", enrichmentMetadata);
            newDataset.setFeatureColumns(featureCols);

            datasetRepository.save(newDataset);

            // 11. Construire la réponse
            DatasetCleanResponse.CleaningStats stats = DatasetCleanResponse.CleaningStats.builder()
                    .originalRows(root.path("original_rows").asLong(0))
                    .cleanedRows(root.path("original_rows").asLong(0))
                    .build();

            return DatasetCleanResponse.builder()
                    .datasetUuid(newDataset.getDatasetUuid())
                    .datasetName(newDataset.getDatasetName())
                    .status(DatasetCleanResponse.CleaningStatus.COMPLETED)
                    .jobId(jobId)
                    .processedFilePath(minioKey)
                    .stats(stats)
                    .build();

        } catch (Exception e) {
            log.error("Erreur lors de l'extraction des features pour le dataset {}", datasetUuid, e);
            throw e;
        } finally {
            // Nettoyage
            cleanup(tempFile, containerInput);
            if (utf8File != null && !utf8File.equals(tempFile)) {
                try {
                    Files.deleteIfExists(utf8File);
                } catch (Exception e) {
                    log.warn("Nettoyage utf8 file failed: {}", e.getMessage());
                }
            }
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (Exception e) {
                    log.warn("Nettoyage output file failed: {}", e.getMessage());
                }
            }
            if (containerOutput != null) {
                try {
                    execDockerCommand("rm", "-f", containerOutput);
                } catch (Exception e) {
                    log.warn("Nettoyage conteneur échoué: {}", e.getMessage());
                }
            }
        }
    }

    // Docker Utils

    private String execDockerCommand(String... commands) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "fraud-ml-pipeline"));
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
                "fraud-ml-pipeline:" + destination);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("Docker copy failed: " + output);
    }

    private void execDockerCopyFromContainer(String containerPath, Path localPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "cp", "fraud-ml-pipeline:" + containerPath,
                localPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor();
        if (exitCode != 0)
            throw new RuntimeException("Docker copy from container failed: " + output);
    }

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

    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private JsonNode extractJsonFromOutput(String output) {
        try {
            return objectMapper.readTree(output);
        } catch (Exception e) {
            // fall through
        }
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
}