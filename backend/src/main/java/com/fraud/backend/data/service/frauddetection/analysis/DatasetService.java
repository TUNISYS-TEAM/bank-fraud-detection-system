package com.fraud.backend.data.service.frauddetection.analysis;

import com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetCleanResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetPreviewResponse;

import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.AccountType;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.service.frauddetection.scoring.BackfillScoringService;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;

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
    private final BackfillScoringService backfillScoringService;
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

    public Page<Dataset> list(String q, DatasetStatus status, DatasetType type, Boolean visibility, Pageable pageable) {
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

        log.info("Uploading dataset of type: {} with name: {}", type, datasetName);
        validateUploadRole(type);

        // On permet désormais plusieurs datasets du même type (ils seront masqués par
        // défaut)

        // Créer dataset : status = null, visibility = false par défaut
        Dataset ds = Dataset.builder()
                .datasetName(datasetName.trim())
                .datasetCode(generateCode())
                .status(null) // nouveau dataset non traité
                .visibility(false) // invisible par défaut
                .type(type)
                .build();

        ds = datasetRepository.save(ds);
        log.info("Created dataset with UUID: {}, status=null, visibility=false", ds.getDatasetUuid());

        String finalFilePath = null;
        long rowCount = 0;
        BigDecimal sizeMb = BigDecimal.ZERO;

        Path tempCsvFile = null;
        Path utf8File = null;
        Path parquetFile = null;
        String containerOutputFile = null;

        // Flags to track which files have been cleaned up
        boolean csvDeleted = false;
        boolean parquetDeleted = false;

        try {
            // 1. Save uploaded file temporarily
            tempCsvFile = Files.createTempFile("upload_", ".csv");
            file.transferTo(tempCsvFile.toFile());
            log.info("Temporary CSV saved: {}", tempCsvFile);

            // 1.5. Validate Columns
            validateCsvColumns(type, tempCsvFile);
            log.info("CSV columns validated successfully for type {}", type);

            // 2. Convert to UTF-8 if needed
            Path utf8CsvFile = convertToUtf8(tempCsvFile);
            if (utf8CsvFile == null) {
                utf8CsvFile = tempCsvFile;
            } else {
                utf8File = utf8CsvFile;
            }

            // 3. Docker paths
            String containerInputFile = "/app/data/" + utf8CsvFile.getFileName().toString();
            containerOutputFile = "/app/data/" + ds.getDatasetUuid() + ".parquet";

            // 4. Ensure data directory exists in container
            execDockerCommand("mkdir", "-p", "/app/data");

            // 5. Copy CSV to container
            execDockerCopy(utf8CsvFile, containerInputFile);
            log.info("CSV copied to container: {}", containerInputFile);

            // 6. Delete UTF-8 file if it's separate
            if (utf8File != null) {
                try {
                    Files.deleteIfExists(utf8File);
                    utf8File = null;
                    log.info("Deleted temporary UTF-8 file: {}", utf8CsvFile);
                } catch (Exception ex) {
                    log.warn("Failed to delete UTF-8 temp file: {}", ex.getMessage());
                }
            }

            // 7. Run conversion command
            List<String> cmd = new ArrayList<>();
            cmd.addAll(List.of(
                    "docker", "exec", "fraud-ml-pipeline",
                    "python", "-m", "ml_pipeline.cli.main",
                    "convert-to-parquet",
                    "--input", containerInputFile,
                    "--output", containerOutputFile));

            log.info("Running conversion command: {}", String.join(" ", cmd));

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

            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Parquet conversion timed out after 5 minutes");
            }

            int exitCode = process.exitValue();
            String result = outputBuilder.toString().trim();

            if (exitCode != 0) {
                log.error("Parquet conversion failed with code {}: {}", exitCode, result);
                throw new RuntimeException("Failed to convert CSV to Parquet");
            }

            // 8. Retrieve converted Parquet file from container
            parquetFile = Files.createTempFile("converted_", ".parquet");
            execDockerCopyFromContainer(containerOutputFile, parquetFile);
            log.info("Parquet file retrieved: {}", parquetFile);

            // 9. Delete container file now that we have it locally
            try {
                execDockerCommand("rm", "-f", containerOutputFile);
                log.info("Removed container file: {}", containerOutputFile);
                containerOutputFile = null;
            } catch (Exception ex) {
                log.warn("Failed to remove container file: {}", containerOutputFile, ex);
            }

            // 10. Upload Parquet to MinIO
            String folder = type.toString().toLowerCase();
            finalFilePath = "data/raw/" + folder + "/" + ds.getDatasetUuid() + "/data.parquet";
            try (InputStream is = Files.newInputStream(parquetFile)) {
                storageService.upload(finalFilePath, is, Files.size(parquetFile), "application/octet-stream");
            }
            log.info("Parquet file uploaded to MinIO: {}", finalFilePath);

            // 11. Get row count and size from Parquet
            rowCount = countParquetRows(parquetFile);
            sizeMb = bytesToMb(Files.size(parquetFile));
            log.info("Dataset converted to Parquet successfully. Row count: {}", rowCount);

            // 12. Delete local Parquet temp file now that it's uploaded
            try {
                Files.deleteIfExists(parquetFile);
                parquetDeleted = true;
                log.info("Temporary Parquet file deleted: {}", parquetFile);
            } catch (Exception ex) {
                log.warn("Failed to delete temporary Parquet file: {}", ex.getMessage());
            }

        } catch (Exception e) {
            log.error("Fatal error during Parquet conversion for dataset {}: {}", ds.getDatasetUuid(), e.getMessage(),
                    e);
            throw new RuntimeException("Échec de la conversion technique du fichier : " + e.getMessage(), e);
        } finally {
            // Clean up any leftover UTF-8 file
            if (utf8File != null && Files.exists(utf8File)) {
                try {
                    Files.deleteIfExists(utf8File);
                    log.info("Cleaned up UTF-8 file in finally: {}", utf8File);
                } catch (Exception ex) {
                    log.warn("Failed to delete UTF-8 file in finally: {}", ex.getMessage());
                }
            }
        }

        // Update dataset metadata (happens in both success and fallback paths)
        ds.updateFileStats(finalFilePath, sizeMb, rowCount);
        ds = datasetRepository.save(ds);
        log.info("Dataset metadata updated with file path: {}", finalFilePath);

        // Skip DB import: data will only be imported when dataset is activated
        // (visibility=true)
        // Clean up the temporary CSV file
        if (tempCsvFile != null && Files.exists(tempCsvFile)) {
            try {
                Files.deleteIfExists(tempCsvFile);
                csvDeleted = true;
                log.info("Temporary CSV file deleted: {}", tempCsvFile);
            } catch (Exception ex) {
                log.warn("Failed to delete temporary CSV file: {}", ex.getMessage());
            }
        }

        // If for some reason the CSV file was not deleted, attempt final cleanup
        if (!csvDeleted && tempCsvFile != null && Files.exists(tempCsvFile)) {
            try {
                Files.deleteIfExists(tempCsvFile);
                log.info("Final cleanup deleted CSV file: {}", tempCsvFile);
            } catch (Exception ex) {
                log.warn("Final cleanup failed to delete CSV file: {}", ex.getMessage());
            }
        }

        // Ensure Parquet temp file is deleted if not already
        if (!parquetDeleted && parquetFile != null && Files.exists(parquetFile)) {
            try {
                Files.deleteIfExists(parquetFile);
                log.info("Final cleanup deleted Parquet file: {}", parquetFile);
            } catch (Exception ex) {
                log.warn("Final cleanup failed to delete Parquet file: {}", ex.getMessage());
            }
        }

        return ds;
    }

    @Transactional
    public void activate(UUID uuid) {
        Dataset target = get(uuid);

        // For types that should have only one visible dataset
        if (shouldEnforceSingleVisibility(target.getType())) {
            Optional<Dataset> visibleOfSameType = datasetRepository.findVisibleByTypeExcluding(target.getType(), uuid);
            if (visibleOfSameType.isPresent()) {
                Dataset other = visibleOfSameType.get();
                throw new IllegalArgumentException(String.format(
                        "Un dataset de type %s est déjà visible et persistant en base : <strong>'%s'</strong>. Veuillez changer son statut d'abord.",
                        target.getType(), other.getDatasetName()));
            }
        }

        target.setVisibility(true);
        datasetRepository.save(target);
        log.info("Dataset {} activated (visibility=true)", uuid);

        // Import data into DB tables when making dataset visible
        if (shouldEnforceSingleVisibility(target.getType())) {
            try {
                importDataFromMinioToDb(target);

                // === NEW: Trigger backfill for the newly imported transactions ===
                if (target.getType() == DatasetType.TRANSACTIONS_VAL) {
                    log.info("New transactions imported from dataset {}. Triggering backfill...", uuid);
                    backfillScoringService.tryStartBackfillIfNeeded();
                }
            } catch (Exception e) {
                log.error("Error importing data from MinIO to DB during activation: {}", e.getMessage(), e);
            }
        }

        // Handle bidirectional sync for training datasets
        if (target.getType() == DatasetType.TRANSACTIONS_TRAIN) {
            activateEnrichedVersion(target);
        } else if (target.getType() == DatasetType.TRANSACTIONS_TRAIN_ENRICHED) {
            activateSourceVersion(target);
        }
    }

    @Transactional
    public void deactivate(UUID uuid) {
        Dataset target = get(uuid);

        // If this dataset was visible, clear its table
        if (target.getVisibility()) {
            clearTableForType(target.getType());
        }

        target.setVisibility(false);
        datasetRepository.save(target);
        log.info("Dataset {} deactivated (visibility=false)", uuid);

        // Handle bidirectional sync for training datasets
        if (target.getType() == DatasetType.TRANSACTIONS_TRAIN) {
            deactivateEnrichedVersion(target);
        } else if (target.getType() == DatasetType.TRANSACTIONS_TRAIN_ENRICHED) {
            deactivateSourceVersion(target);
        }
    }

    private boolean shouldEnforceSingleVisibility(DatasetType type) {
        return type == DatasetType.CLIENTS ||
                type == DatasetType.ACCOUNTS ||
                type == DatasetType.TRANSACTIONS_VAL;
    }

    private void clearTableForType(DatasetType type) {
        log.info("Clearing database tables for dataset type: {}", type);

        try {
            switch (type) {
                case CLIENTS:
                    // Ordre : enfants → parents
                    jdbcTemplate.execute("DELETE FROM fraud_alert");
                    jdbcTemplate.execute("DELETE FROM client_behavior_history");
                    jdbcTemplate.execute("DELETE FROM client_behavior");
                    jdbcTemplate.execute("DELETE FROM transaction");
                    jdbcTemplate.execute("DELETE FROM account");
                    jdbcTemplate.execute("DELETE FROM client");
                    log.info("All client-related tables cleared");
                    break;

                case ACCOUNTS:
                    jdbcTemplate.execute("DELETE FROM fraud_alert");
                    jdbcTemplate.execute("DELETE FROM client_behavior_history");
                    jdbcTemplate.execute("DELETE FROM client_behavior");
                    jdbcTemplate.execute("DELETE FROM transaction");
                    jdbcTemplate.execute("DELETE FROM account");
                    log.info("All account-related tables cleared");
                    break;

                case TRANSACTIONS_VAL:
                    jdbcTemplate.execute("DELETE FROM fraud_alert");
                    jdbcTemplate.execute("DELETE FROM client_behavior_history");
                    jdbcTemplate.execute("DELETE FROM client_behavior");
                    jdbcTemplate.execute("DELETE FROM transaction");
                    log.info("All transaction validation tables cleared");
                    break;

                case TRANSACTIONS_TRAIN:
                case TRANSACTIONS_TRAIN_ENRICHED:
                    // These don't have database tables to clear
                    log.info("No database tables to clear for type: {}", type);
                    break;

                default:
                    log.warn("Unknown dataset type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error clearing tables for type {}: {}", type, e.getMessage(), e);
            throw new RuntimeException("Failed to clear database tables", e);
        }
    }

    /**
     * Import data from a Parquet file stored in MinIO directly into the appropriate
     * DB table.
     * Downloads the Parquet, reads it with AvroParquetReader, and batch-inserts via
     * jdbcTemplate.
     */
    private void importDataFromMinioToDb(Dataset ds) throws IOException {
        if (ds.getFilePath() == null || ds.getFilePath().isBlank()) {
            log.warn("Dataset {} has no file path, skipping DB import", ds.getDatasetUuid());
            return;
        }

        log.info("Importing data from MinIO Parquet into DB for dataset {} (type={})",
                ds.getDatasetUuid(), ds.getType());

        Path tempParquetFile = Files.createTempFile("import_parquet_", ".parquet");
        try {
            // 1. Download Parquet from MinIO
            try (InputStream in = storageService.download(ds.getFilePath())) {
                Files.copy(in, tempParquetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Parquet downloaded to temp: {} ({} bytes)", tempParquetFile, Files.size(tempParquetFile));

            // 2. Configure Hadoop for Parquet reading
            org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(
                    tempParquetFile.toAbsolutePath().toString());
            org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
            conf.set("fs.defaultFS", "file:///");
            conf.setBoolean("fs.file.impl.disable.cache", true);
            conf.set("hadoop.security.authentication", "simple");
            String tempDir = System.getProperty("java.io.tmpdir");
            System.setProperty("hadoop.home.dir", tempDir);
            conf.set("hadoop.home.dir", tempDir);

            // 3. Resolve column names from Parquet schema
            Map<String, String> columnMapping = resolveParquetColumns(hadoopPath, conf, ds.getType());

            // 4. Read and import based on type
            switch (ds.getType()) {
                case CLIENTS:
                    importClientsFromParquet(hadoopPath, conf, columnMapping);
                    break;
                case ACCOUNTS:
                    importAccountsFromParquet(hadoopPath, conf, columnMapping);
                    break;
                case TRANSACTIONS_VAL:
                    importTransactionsValFromParquet(hadoopPath, conf, columnMapping);
                    break;
                default:
                    log.warn("No DB import defined for type: {}", ds.getType());
            }
        } finally {
            try {
                Files.deleteIfExists(tempParquetFile);
                log.info("Temp Parquet file deleted: {}", tempParquetFile);
            } catch (Exception e) {
                log.warn("Failed to delete temp Parquet file: {}", e.getMessage());
            }
        }
    }

    private String getStringField(GenericRecord record, String fieldName) {
        if (fieldName == null)
            return null;
        Object val = record.get(fieldName);
        return val != null ? val.toString() : null;
    }

    /**
     * Resolve Parquet column names to our standard keys using the
     * REQUIRED_COLUMNS_BY_TYPE aliases.
     * Returns a map: standardKey -> actualParquetColumnName
     */
    private Map<String, String> resolveParquetColumns(org.apache.hadoop.fs.Path hadoopPath,
            org.apache.hadoop.conf.Configuration conf, DatasetType type) throws IOException {

        Map<String, List<String>> requiredMap = REQUIRED_COLUMNS_BY_TYPE.get(type);
        if (requiredMap == null)
            return Collections.emptyMap();

        // Read schema from Parquet to get actual column names
        Set<String> parquetColumns = new HashSet<>();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(hadoopPath).withConf(conf).build()) {
            GenericRecord first = reader.read();
            if (first != null) {
                for (org.apache.avro.Schema.Field f : first.getSchema().getFields()) {
                    parquetColumns.add(f.name());
                }
            }
        }
        log.info("Parquet columns detected: {}", parquetColumns);

        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : requiredMap.entrySet()) {
            String standardKey = entry.getKey();
            for (String alias : entry.getValue()) {
                String found = parquetColumns.stream()
                        .filter(c -> c.equalsIgnoreCase(alias))
                        .findFirst().orElse(null);
                if (found != null) {
                    mapping.put(standardKey, found);
                    break;
                }
            }
            if (!mapping.containsKey(standardKey)) {
                log.warn("Column '{}' not found in Parquet (tried aliases: {})", standardKey, entry.getValue());
            }
        }
        log.info("Parquet column mapping resolved: {}", mapping);
        return mapping;
    }

    private void importClientsFromParquet(org.apache.hadoop.fs.Path hadoopPath,
            org.apache.hadoop.conf.Configuration conf, Map<String, String> colMap) throws IOException {
        // Clear existing data
        jdbcTemplate.execute("DELETE FROM fraud_alert");
        jdbcTemplate.execute("DELETE FROM client_behavior_history");
        jdbcTemplate.execute("DELETE FROM client_behavior");
        jdbcTemplate.execute("DELETE FROM transaction");
        jdbcTemplate.execute("DELETE FROM account");
        jdbcTemplate.execute("DELETE FROM client");

        String sql = "INSERT INTO client (client_id, first_name, last_name, date_of_birth, national_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(hadoopPath).withConf(conf).build()) {

            List<Object[]> batch = new ArrayList<>();
            int count = 0;
            Timestamp now = new Timestamp(System.currentTimeMillis());
            GenericRecord record;

            while ((record = reader.read()) != null) {
                count++;
                try {
                    String fullName = getStringField(record, colMap.get("Full_Name"));
                    String firstName = "";
                    String lastName = "";
                    if (fullName != null && !fullName.isBlank()) {
                        String[] parts = fullName.split(" ", 2);
                        firstName = parts[0];
                        if (parts.length > 1)
                            lastName = parts[1];
                    }

                    Long clientId = parseLongSafe(getStringField(record, colMap.get("Client_Id")));
                    LocalDate dob = parseLocalDateSafe(getStringField(record, colMap.get("Date_of_Birth")));
                    String nationalId = getStringField(record, colMap.get("National_Id"));
                    LocalDateTime createdAt = parseLocalDateTimeSafe(getStringField(record, colMap.get("Created_At")));
                    Timestamp tsCreatedAt = (createdAt != null) ? Timestamp.valueOf(createdAt) : now;

                    batch.add(new Object[] {
                            clientId, firstName, lastName,
                            dob != null ? java.sql.Date.valueOf(dob) : null,
                            nationalId, tsCreatedAt, now
                    });

                    if (batch.size() >= BATCH_SIZE) {
                        jdbcTemplate.batchUpdate(sql, batch);
                        batch.clear();
                    }
                } catch (Exception e) {
                    log.error("Error importing client at record {}: {}", count, e.getMessage());
                }
            }

            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
            log.info("Import clients from Parquet done: {} records inserted", count);
        }
    }

    private void importAccountsFromParquet(org.apache.hadoop.fs.Path hadoopPath,
            org.apache.hadoop.conf.Configuration conf, Map<String, String> colMap) throws IOException {
        // Clear existing accounts
        jdbcTemplate.execute("DELETE FROM fraud_alert");
        jdbcTemplate.execute("DELETE FROM client_behavior_history");
        jdbcTemplate.execute("DELETE FROM client_behavior");
        jdbcTemplate.execute("DELETE FROM transaction");
        jdbcTemplate.execute("DELETE FROM account");

        Set<Long> existingClientIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT client_id FROM client", Long.class));
        log.info("Pre-loaded {} client IDs", existingClientIds.size());

        String sql = "INSERT INTO account (account_number, account_type, balance, currency, is_active, risk_level, client_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(hadoopPath).withConf(conf).build()) {

            List<Object[]> batch = new ArrayList<>();
            int count = 0;
            int skipped = 0;
            Timestamp now = new Timestamp(System.currentTimeMillis());
            GenericRecord record;

            while ((record = reader.read()) != null) {
                count++;
                try {
                    Long clientNumId = parseLongSafe(getStringField(record, colMap.get("Client_Id")));
                    if (clientNumId == null || !existingClientIds.contains(clientNumId)) {
                        skipped++;
                        continue;
                    }

                    String statusStr = getStringField(record, colMap.get("Status"));
                    boolean isActive = statusStr != null && (statusStr.equalsIgnoreCase("active")
                            || statusStr.equalsIgnoreCase("true") || statusStr.equals("1")
                            || statusStr.equalsIgnoreCase("actif"));

                    String accountNumber = getStringField(record, colMap.get("Account_Id"));
                    AccountType accountType = parseAccountType(getStringField(record, colMap.get("Account_Type")));
                    BigDecimal balance = parseBigDecimalSafe(getStringField(record, colMap.get("Balance")));
                    LocalDateTime createdAt = parseLocalDateTimeSafe(getStringField(record, colMap.get("Created_At")));
                    Timestamp tsCreatedAt = (createdAt != null) ? Timestamp.valueOf(createdAt) : now;

                    batch.add(new Object[] {
                            accountNumber != null ? accountNumber.trim() : null,
                            accountType.name(), balance, "TND", isActive, "LOW",
                            clientNumId, tsCreatedAt, now
                    });

                    if (batch.size() >= BATCH_SIZE) {
                        jdbcTemplate.batchUpdate(sql, batch);
                        batch.clear();
                    }
                } catch (Exception e) {
                    log.error("Error importing account at record {}: {}", count, e.getMessage());
                    skipped++;
                }
            }

            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
            log.info("Import accounts from Parquet done: {} processed, {} inserted, {} skipped",
                    count, count - skipped, skipped);
        }
    }

    private void importTransactionsValFromParquet(org.apache.hadoop.fs.Path hadoopPath,
            org.apache.hadoop.conf.Configuration conf, Map<String, String> colMap) throws IOException {
        boolean triggerDisabled = false;
        try {
            jdbcTemplate.execute("ALTER TABLE transaction DISABLE TRIGGER after_transaction_insert;");
            triggerDisabled = true;

            // Clear existing transactions
            jdbcTemplate.execute("DELETE FROM fraud_alert");
            jdbcTemplate.execute("DELETE FROM client_behavior_history");
            jdbcTemplate.execute("DELETE FROM client_behavior");
            jdbcTemplate.execute("DELETE FROM transaction");

            Map<String, Long> accountNumberToId = jdbcTemplate.query(
                    "SELECT account_number, id FROM account",
                    (rs, rowNum) -> Map.entry(rs.getString("account_number"), rs.getLong("id"))).stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            log.info("{} accounts loaded (account_number -> id)", accountNumberToId.size());

            String sql = "INSERT INTO transaction (transaction_uuid, account_id, amount, currency, transaction_date, "
                    + "merchant_category, channel, status, postal_code_merchant, postal_code_client, device_id, "
                    + "ip_address, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (ParquetReader<GenericRecord> reader = AvroParquetReader
                    .<GenericRecord>builder(hadoopPath).withConf(conf).build()) {

                List<Object[]> batch = new ArrayList<>();
                Timestamp now = new Timestamp(System.currentTimeMillis());
                int count = 0;
                int skipped = 0;
                GenericRecord record;

                while ((record = reader.read()) != null) {
                    try {
                        String rawAccountNumber = getStringField(record, colMap.get("Account_Id"));
                        if (rawAccountNumber == null || rawAccountNumber.isBlank()) {
                            skipped++;
                            continue;
                        }

                        String accountNumber = cleanStringSafe(rawAccountNumber);
                        Long accountId = accountNumberToId.get(accountNumber);
                        if (accountId == null) {
                            if (skipped < 5) {
                                log.warn("Account not found for account_number: '{}'", accountNumber);
                            }
                            skipped++;
                            continue;
                        }

                        BigDecimal amount = parseBigDecimalSafe(
                                cleanStringSafe(getStringField(record, colMap.get("TX_Amount"))));
                        LocalDateTime txTime = parseLocalDateTimeSafe(
                                cleanStringSafe(getStringField(record, colMap.get("TX_Time"))));

                        String category = cleanStringSafe(getStringField(record, colMap.get("category")));
                        String channel = parseChannelSafe(
                                cleanStringSafe(getStringField(record, colMap.get("channel"))));
                        String mPostal = cleanStringSafe(getStringField(record, colMap.get("Merchant_Postal_Code")));
                        String cPostal = cleanStringSafe(getStringField(record, colMap.get("Customer_Postal_Code")));
                        String deviceId = cleanStringSafe(getStringField(record, colMap.get("Device_Id")));
                        String ipAddress = cleanStringSafe(getStringField(record, colMap.get("ip_address")));
                        String cur = cleanStringSafe(getStringField(record, colMap.get("currency")));
                        if (cur == null || cur.isBlank())
                            cur = "TND";

                        batch.add(new Object[] {
                                UUID.randomUUID(), accountId, amount, cur,
                                txTime != null ? Timestamp.valueOf(txTime) : now,
                                category, channel, "COMPLETED",
                                mPostal, cPostal, deviceId, ipAddress, now, now
                        });

                        if (batch.size() >= BATCH_SIZE) {
                            jdbcTemplate.batchUpdate(sql, batch);
                            batch.clear();
                        }
                        count++;
                    } catch (Exception e) {
                        log.error("Error importing transaction: {}", e.getMessage());
                        skipped++;
                    }
                }

                if (!batch.isEmpty()) {
                    jdbcTemplate.batchUpdate(sql, batch);
                }
                log.info("Import transactions from Parquet done: {} inserted, {} skipped", count, skipped);
            }
        } catch (Exception e) {
            log.error("Fatal error during TRANSACTIONS_VAL import from Parquet: ", e);
            throw new IOException("Import failed: " + e.getMessage(), e);
        } finally {
            if (triggerDisabled) {
                try {
                    jdbcTemplate.execute("ALTER TABLE transaction ENABLE TRIGGER after_transaction_insert;");
                    log.info("Trigger after_transaction_insert re-enabled");
                } catch (Exception e) {
                    log.warn("Error re-enabling trigger: {}", e.getMessage());
                }
            }
        }
    }

    private void activateEnrichedVersion(Dataset sourceDataset) {
        try {
            UUID sourceUuid = sourceDataset.getDatasetUuid();
            log.info("Looking for enriched version of source dataset: {}", sourceUuid);

            List<Dataset> enrichedDatasets = datasetRepository.findByType(
                    DatasetType.TRANSACTIONS_TRAIN_ENRICHED,
                    Pageable.unpaged()).getContent();

            for (Dataset enriched : enrichedDatasets) {
                Map<String, Object> featureCols = enriched.getFeatureColumns();
                if (featureCols != null && featureCols.containsKey("enrichment")) {
                    Map<String, Object> enrichment = (Map<String, Object>) featureCols.get("enrichment");
                    if (enrichment != null && sourceUuid.toString().equals(enrichment.get("sourceDatasetId"))) {
                        enriched.setVisibility(true);
                        datasetRepository.save(enriched);
                        log.info("✓ Also activated enriched dataset {} (visibility=true)",
                                enriched.getDatasetUuid());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Could not activate enriched version for dataset {}: {}",
                    sourceDataset.getDatasetUuid(), e.getMessage(), e);
        }
    }

    private void deactivateEnrichedVersion(Dataset sourceDataset) {
        try {
            UUID sourceUuid = sourceDataset.getDatasetUuid();

            List<Dataset> enrichedDatasets = datasetRepository.findByType(
                    DatasetType.TRANSACTIONS_TRAIN_ENRICHED,
                    Pageable.unpaged()).getContent();

            for (Dataset enriched : enrichedDatasets) {
                Map<String, Object> featureCols = enriched.getFeatureColumns();
                if (featureCols != null && featureCols.containsKey("enrichment")) {
                    Map<String, Object> enrichment = (Map<String, Object>) featureCols.get("enrichment");
                    if (enrichment != null && sourceUuid.toString().equals(enrichment.get("sourceDatasetId"))) {
                        enriched.setVisibility(false);
                        datasetRepository.save(enriched);
                        log.info("Also deactivated enriched dataset {} (visibility=false)",
                                enriched.getDatasetUuid());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not deactivate enriched version for dataset {}: {}",
                    sourceDataset.getDatasetUuid(), e.getMessage());
        }
    }

    private void activateSourceVersion(Dataset enrichedDataset) {
        try {
            Map<String, Object> featureCols = enrichedDataset.getFeatureColumns();
            if (featureCols == null || !featureCols.containsKey("enrichment")) {
                return;
            }

            Map<String, Object> enrichment = (Map<String, Object>) featureCols.get("enrichment");
            if (enrichment == null || !enrichment.containsKey("sourceDatasetId")) {
                return;
            }

            String sourceIdStr = (String) enrichment.get("sourceDatasetId");
            UUID sourceUuid = UUID.fromString(sourceIdStr);

            Optional<Dataset> sourceOpt = datasetRepository.findByDatasetUuid(sourceUuid);
            if (sourceOpt.isPresent()) {
                Dataset source = sourceOpt.get();
                source.setVisibility(true);
                datasetRepository.save(source);
                log.info("Also activated source dataset {} (visibility=true)", sourceUuid);
            }
        } catch (Exception e) {
            log.error("Could not activate source version for enriched dataset {}: {}",
                    enrichedDataset.getDatasetUuid(), e.getMessage(), e);
        }
    }

    private void deactivateSourceVersion(Dataset enrichedDataset) {
        try {
            Map<String, Object> featureCols = enrichedDataset.getFeatureColumns();
            if (featureCols == null || !featureCols.containsKey("enrichment"))
                return;

            Map<String, Object> enrichment = (Map<String, Object>) featureCols.get("enrichment");
            if (enrichment == null || !enrichment.containsKey("sourceDatasetId"))
                return;

            String sourceIdStr = (String) enrichment.get("sourceDatasetId");
            UUID sourceUuid = UUID.fromString(sourceIdStr);

            Optional<Dataset> sourceOpt = datasetRepository.findByDatasetUuid(sourceUuid);
            if (sourceOpt.isPresent()) {
                Dataset source = sourceOpt.get();
                source.setVisibility(false);
                datasetRepository.save(source);
                log.info("Also deactivated source dataset {} (visibility=false)", sourceUuid);
            }
        } catch (Exception e) {
            log.warn("Could not deactivate source version for enriched dataset {}: {}",
                    enrichedDataset.getDatasetUuid(), e.getMessage());
        }
    }

    @Transactional
    public void delete(UUID uuid) {
        Dataset ds = get(uuid);

        // If this is a TRANSACTIONS_TRAIN dataset, also delete its enriched version
        // first
        if (ds.getType() == DatasetType.TRANSACTIONS_TRAIN) {
            try {
                List<Dataset> enrichedDatasets = datasetRepository.findByType(
                        DatasetType.TRANSACTIONS_TRAIN_ENRICHED,
                        Pageable.unpaged()).getContent();

                for (Dataset enriched : enrichedDatasets) {
                    Map<String, Object> enrichedFeatureCols = enriched.getFeatureColumns();
                    if (enrichedFeatureCols != null && enrichedFeatureCols.containsKey("enrichment")) {
                        Map<String, Object> enrichedMeta = (Map<String, Object>) enrichedFeatureCols
                                .get("enrichment");
                        if (enrichedMeta != null &&
                                uuid.toString().equals(enrichedMeta.get("sourceDatasetId"))) {

                            log.info("Found enriched dataset {} to delete along with source",
                                    enriched.getDatasetUuid());

                            // Delete the enriched dataset's file from MinIO
                            if (enriched.getFilePath() != null && !enriched.getFilePath().isBlank()) {
                                try {
                                    storageService.delete(enriched.getFilePath());
                                    log.info("Deleted enriched dataset file from MinIO: {}",
                                            enriched.getFilePath());
                                } catch (Exception e) {
                                    log.warn("Failed to delete enriched dataset file from MinIO: {}",
                                            e.getMessage());
                                }
                            }

                            // Delete the enriched dataset from database
                            datasetRepository.delete(enriched);
                            log.info("Deleted enriched dataset: {}", enriched.getDatasetUuid());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not delete enriched version for dataset {}: {}", uuid, e.getMessage());
            }
        }

        // Clear database tables if this dataset is of a type that has tables
        if (shouldEnforceSingleVisibility(ds.getType())) {
            clearTableForType(ds.getType());
        }

        try {
            // Supprimer le fichier du datalake (MinIO) for the main dataset
            if (ds.getFilePath() != null && !ds.getFilePath().isBlank()) {
                storageService.delete(ds.getFilePath());
            }

            // Supprimer l'entrée dataset principale
            datasetRepository.delete(ds);
            datasetRepository.flush();

            log.info("Successfully deleted dataset {} and its associated resources", uuid);

        } catch (DataIntegrityViolationException e) {
            log.warn("Échec de la suppression du dataset {} ({}) : {}", ds.getDatasetName(), uuid, e.getMessage());
            throw new IllegalArgumentException(
                    "Impossible de supprimer ce dataset car des données sont référencées par d'autres modules.");
        }
    }

    @Transactional
    public Dataset update(UUID uuid, DatasetUpdateRequest request) {
        Dataset ds = get(uuid);
        boolean visibilityChanged = false;
        Boolean oldVisibility = ds.getVisibility();

        if (request.getDatasetName() != null && !request.getDatasetName().isBlank()) {
            ds.setDatasetName(request.getDatasetName().trim());
        }

        if (request.getStatus() != null) {
            ds.setStatus(request.getStatus());
        }

        if (request.getVisibility() != null && !request.getVisibility().equals(oldVisibility)) {
            // If trying to set visibility to false
            if (!request.getVisibility() && oldVisibility) {
                // Clear the corresponding table if this dataset was visible
                clearTableForType(ds.getType());
            }

            // If trying to set visibility to true
            if (request.getVisibility() && !oldVisibility) {
                // For types that should have only one visible dataset
                if (shouldEnforceSingleVisibility(ds.getType())) {
                    Optional<Dataset> visibleOfSameType = datasetRepository.findVisibleByTypeExcluding(ds.getType(),
                            uuid);
                    if (visibleOfSameType.isPresent()) {
                        Dataset other = visibleOfSameType.get();
                        throw new IllegalArgumentException(String.format(
                                "Un dataset de type %s est déjà visible et persistant en base : <strong>'%s'</strong>. Veuillez changer son statut d'abord.",
                                ds.getType(), other.getDatasetName()));
                    }
                }
            }

            ds.setVisibility(request.getVisibility());
            visibilityChanged = true;
        }

        ds = datasetRepository.save(ds);

        // If visibility changed to true, import data into DB for business types
        if (visibilityChanged && ds.getVisibility() && shouldEnforceSingleVisibility(ds.getType())) {
            try {
                importDataFromMinioToDb(ds);

                // Trigger backfill for newly imported validation transactions
                if (ds.getType() == DatasetType.TRANSACTIONS_VAL) {
                    log.info("New transactions imported from dataset {} during update. Triggering backfill...", uuid);
                    backfillScoringService.tryStartBackfillIfNeeded();
                }
            } catch (Exception e) {
                log.error("Error importing data from MinIO to DB during update: {}", e.getMessage(), e);
            }
        }

        // If visibility changed, sync the related dataset for training types
        if (visibilityChanged) {
            if (ds.getType() == DatasetType.TRANSACTIONS_TRAIN) {
                if (ds.getVisibility()) {
                    activateEnrichedVersion(ds);
                } else {
                    deactivateEnrichedVersion(ds);
                }
            } else if (ds.getType() == DatasetType.TRANSACTIONS_TRAIN_ENRICHED) {
                if (ds.getVisibility()) {
                    activateSourceVersion(ds);
                } else {
                    deactivateSourceVersion(ds);
                }
            }
        }

        return ds;
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
                    .status(dataset.getStatus() != null ? dataset.getStatus().toString() : null)
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
                .status(dataset.getStatus() != null ? dataset.getStatus().toString() : null)
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

        String filePath = ds.getFilePath();
        if (filePath.toLowerCase().endsWith(".parquet")) {
            log.info("Downloading Parquet dataset {} as CSV", uuid);
            return downloadAsCsv(ds);
        }

        return storageService.download(filePath);
    }

    private InputStream downloadAsCsv(Dataset ds) {
        Path tempParquet = null;
        Path tempCsv = null;
        String containerInput = "/app/data/export_" + ds.getDatasetUuid() + ".parquet";
        String containerOutput = "/app/data/export_" + ds.getDatasetUuid() + ".csv";

        try {
            // 1. Download Parquet from MinIO to local temp
            tempParquet = Files.createTempFile("export_in_", ".parquet");
            try (InputStream in = storageService.download(ds.getFilePath())) {
                Files.copy(in, tempParquet, StandardCopyOption.REPLACE_EXISTING);
            }

            // 2. Ensure data directory exists in container
            execDockerCommand("mkdir", "-p", "/app/data");

            // 3. Copy Parquet to container
            execDockerCopy(tempParquet, containerInput);

            // 4. Convert via Python CLI
            execDockerCommand("python", "-m", "ml_pipeline.cli.main", "convert-to-csv",
                    "--input", containerInput,
                    "--output", containerOutput);

            // 5. Copy CSV back from container
            tempCsv = Files.createTempFile("export_out_", ".csv");
            execDockerCopyFromContainer(containerOutput, tempCsv);

            // 6. Clean up container files
            try {
                execDockerCommand("rm", "-f", containerInput, containerOutput);
            } catch (Exception e) {
                log.warn("Failed to clean up container files after export: {}", e.getMessage());
            }

            // 7. Read CSV into memory to return stream and delete temp file
            byte[] csvBytes = Files.readAllBytes(tempCsv);
            return new ByteArrayInputStream(csvBytes);

        } catch (Exception e) {
            log.error("Failed to convert Parquet to CSV for export: {}", e.getMessage(), e);
            throw new RuntimeException("Export failed: " + e.getMessage());
        } finally {
            try {
                if (tempParquet != null)
                    Files.deleteIfExists(tempParquet);
                if (tempCsv != null)
                    Files.deleteIfExists(tempCsv);
            } catch (IOException e) {
                log.warn("Failed to delete temp files: {}", e.getMessage());
            }
        }
    }

    // ==================== STATS ====================

    public long count(List<DatasetType> types) {
        List<DatasetType> targetTypes = (types != null && !types.isEmpty()) ? types : ADMIN_KPI_TYPES;
        long result = datasetRepository.countByTypeIn(targetTypes);
        log.info("KPI Count for types {}: {}", targetTypes, result);
        return result;
    }

    public BigDecimal getTotalSize(List<DatasetType> types) {
        List<DatasetType> targetTypes = (types != null && !types.isEmpty()) ? types : ADMIN_KPI_TYPES;
        BigDecimal result = datasetRepository.sumFileSizeMbByTypeIn(targetTypes);
        log.info("KPI TotalSize for types {}: {} Mo", targetTypes, result);
        return result;
    }

    public LocalDateTime getLatestImportDate(List<DatasetType> types) {
        List<DatasetType> targetTypes = (types != null && !types.isEmpty()) ? types : ADMIN_KPI_TYPES;
        LocalDateTime result = datasetRepository.findLatestImportedAtByTypeIn(targetTypes);
        log.info("KPI LatestImportDate for types {}: {}", targetTypes, result);
        return result;
    }

    // ==================== PRIVATE HELPERS ====================

    private BigDecimal bytesToMb(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BigDecimal.valueOf(1024.0 * 1024.0), 2, RoundingMode.HALF_UP);
    }

    private String generateCode() {
        return "DS-" + System.currentTimeMillis();
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
        // Only datasets with status PROCESSED or CLEANED and visibility true should be
        // considered for training
        List<DatasetStatus> statuses = List.of(DatasetStatus.PROCESSED, DatasetStatus.CLEANED);

        if (q != null && !q.isBlank()) {
            return datasetRepository.findTrainingDatasetsBySearchTerm(
                    DatasetType.TRANSACTIONS_TRAIN_ENRICHED,
                    statuses,
                    q.trim(),
                    pageable);
        }

        return datasetRepository.findByTypeAndStatusInAndVisibilityTrue(
                DatasetType.TRANSACTIONS_TRAIN_ENRICHED,
                statuses,
                pageable);
    }

    public Page<Dataset> listAllTrainingDatasets(String q, Pageable pageable) {
        if (q != null && !q.isBlank()) {
            // Search with term across all TRANSACTIONS_TRAIN_ENRICHED datasets with
            // visibility true
            return datasetRepository.findByTypeAndSearchTermAndVisibilityTrue(
                    DatasetType.TRANSACTIONS_TRAIN_ENRICHED,
                    q.trim(),
                    pageable);
        }

        return datasetRepository.findByTypeAndVisibilityTrue(
                DatasetType.TRANSACTIONS_TRAIN_ENRICHED,
                pageable);
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

            // 3. Préparer les répertoires dans le conteneur
            execDockerCommand("mkdir", "-p", "/app/data");
            execDockerCommand("mkdir", "-p", "/app/data/enriched");
            execDockerCommand("mkdir", "-p", "/app/output");

            // 4. Copier dans le conteneur
            containerInput = "/app/data/" + utf8File.getFileName().toString();
            execDockerCopy(utf8File, containerInput);
            log.info("Fichier copié dans le conteneur: {}", containerInput);

            // 5. Construire la commande Python
            List<String> cmd = new ArrayList<>();
            cmd.addAll(List.of(
                    "docker", "exec", "fraud-ml-pipeline",
                    "python", "-m", "ml_pipeline.cli.main",
                    "extract-behavior",
                    "--input", containerInput,
                    "--dataset-id", sourceDataset.getDatasetUuid().toString(),
                    "--job-id", jobId,
                    "--output", "/app/data/enriched/enriched_" + sourceDataset.getDatasetUuid() + ".parquet"));

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

            // 6. Extraire le JSON de sortie
            JsonNode root = extractJsonFromOutput(result);
            if (root == null) {
                throw new RuntimeException("Aucun JSON valide trouvé dans la sortie");
            }

            if (root.has("status") && "error".equals(root.get("status").asText())) {
                throw new RuntimeException("Erreur Python: " + root.get("error").asText());
            }

            // 7. Récupérer le chemin du fichier de sortie
            String outputFilename = "enriched_" + sourceDataset.getDatasetUuid() + ".parquet";
            containerOutput = "/app/data/enriched/" + outputFilename;
            log.info("Recherche du fichier dans le conteneur: {}", containerOutput);

            // 8. Télécharger le fichier depuis le conteneur
            outputFile = Files.createTempFile("enriched_", ".parquet");
            execDockerCopyFromContainer(containerOutput, outputFile);
            log.info("Fichier enrichi récupéré: {}", outputFile);

            // 9. METTRE À JOUR LE DATASET SOURCE EN STATUS PROCESSED
            sourceDataset.setStatus(DatasetStatus.PROCESSED);

            // Ajouter les métadonnées d'enrichissement au dataset source
            Map<String, Object> sourceFeatureCols = sourceDataset.getFeatureColumns();
            if (sourceFeatureCols == null) {
                sourceFeatureCols = new HashMap<>();
            }

            Map<String, Object> sourceEnrichmentMetadata = new HashMap<>();
            sourceEnrichmentMetadata.put("extractedAt", LocalDateTime.now().toString());
            sourceEnrichmentMetadata.put("jobId", jobId);
            sourceEnrichmentMetadata.put("enrichedDatasetCreated", true);

            JsonNode columnsAddedNode = root.get("columns_added");
            if (columnsAddedNode != null && columnsAddedNode.isArray()) {
                List<String> columnsAdded = new ArrayList<>();
                for (JsonNode col : columnsAddedNode) {
                    columnsAdded.add(col.asText());
                }
                sourceEnrichmentMetadata.put("columnsExtracted", columnsAdded);
            }

            sourceFeatureCols.put("enrichment", sourceEnrichmentMetadata);
            sourceDataset.setFeatureColumns(sourceFeatureCols);

            // Sauvegarder le dataset source mis à jour
            datasetRepository.save(sourceDataset);
            log.info("Dataset source {} mis à jour avec status PROCESSED",
                    sourceDataset.getDatasetUuid());

            // 10. CRÉER UN NOUVEAU DATASET (copie enrichie) avec status PROCESSED,
            // visibility=false et type TRANSACTIONS_TRAIN_ENRICHED
            String newDatasetName = sourceDataset.getDatasetName() + " (enriched)";
            String newDatasetCode = generateCode();

            newDataset = Dataset.builder()
                    .datasetName(newDatasetName)
                    .datasetCode(newDatasetCode)
                    .status(DatasetStatus.PROCESSED)
                    .visibility(false)
                    .type(DatasetType.TRANSACTIONS_TRAIN_ENRICHED)
                    .build();

            // Save immediately to ensure status is persisted
            newDataset = datasetRepository.save(newDataset);
            log.info("Nouveau dataset enrichi créé avec status: {}, visibility=false et type: {}",
                    newDataset.getStatus(), newDataset.getType());

            // 11. Upload du fichier enrichi vers MinIO pour le NOUVEAU dataset
            String minioKey = "data/enriched/transactions/" + newDataset.getDatasetUuid() + "/" + outputFilename;
            try (InputStream is = Files.newInputStream(outputFile)) {
                storageService.upload(minioKey, is, Files.size(outputFile), "application/octet-stream");
            }
            log.info("Fichier enrichi uploadé vers MinIO: {}", minioKey);

            // 12. Mettre à jour les stats du nouveau dataset
            long rowCount = root.path("original_rows").asLong(0);
            BigDecimal fileSizeMb = bytesToMb(Files.size(outputFile));
            newDataset.updateFileStats(minioKey, fileSizeMb, rowCount);

            // 13. Ajouter les métadonnées d'enrichissement au nouveau dataset
            Map<String, Object> featureCols = newDataset.getFeatureColumns();
            if (featureCols == null) {
                featureCols = new HashMap<>();
            }

            Map<String, Object> enrichmentMetadata = new HashMap<>();
            enrichmentMetadata.put("sourceDatasetId", sourceDataset.getDatasetUuid().toString());
            enrichmentMetadata.put("sourceDatasetName", sourceDataset.getDatasetName());
            enrichmentMetadata.put("sourceDatasetType", sourceDataset.getType().toString());
            enrichmentMetadata.put("jobId", jobId);
            enrichmentMetadata.put("extractedAt", LocalDateTime.now().toString());

            if (columnsAddedNode != null && columnsAddedNode.isArray()) {
                List<String> columnsAdded = new ArrayList<>();
                for (JsonNode col : columnsAddedNode) {
                    columnsAdded.add(col.asText());
                }
                enrichmentMetadata.put("columnsAdded", columnsAdded);
            }

            featureCols.put("enrichment", enrichmentMetadata);
            newDataset.setFeatureColumns(featureCols);

            // Final save with all metadata
            newDataset = datasetRepository.save(newDataset);
            log.info("Dataset enrichi final sauvegardé avec status: {}, visibility=false et type: {}",
                    newDataset.getStatus(), newDataset.getType());

            // 14. Supprimer le fichier du conteneur après l'avoir récupéré
            try {
                execDockerCommand("rm", "-f", containerOutput);
                log.info("Fichier conteneur supprimé: {}", containerOutput);
            } catch (Exception e) {
                log.warn("Impossible de supprimer le fichier conteneur: {}", e.getMessage());
            }

            // 15. Construire la réponse
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
            // Nettoyage des fichiers temporaires
            try {
                if (tempFile != null)
                    Files.deleteIfExists(tempFile);
                if (utf8File != null && !utf8File.equals(tempFile))
                    Files.deleteIfExists(utf8File);
                if (outputFile != null)
                    Files.deleteIfExists(outputFile);
            } catch (Exception e) {
                log.warn("Erreur lors du nettoyage des fichiers temporaires: {}", e.getMessage());
            }

            // Nettoyage du fichier d'entrée dans le conteneur
            if (containerInput != null) {
                try {
                    execDockerCommand("rm", "-f", containerInput);
                } catch (Exception e) {
                    log.warn("Erreur lors du nettoyage du fichier d'entrée dans le conteneur: {}", e.getMessage());
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

    private long countParquetRows(Path parquetFile) throws IOException {
        org.apache.hadoop.fs.Path hadoopPath = new org.apache.hadoop.fs.Path(parquetFile.toAbsolutePath().toString());
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        conf.set("fs.defaultFS", "file:///");
        conf.setBoolean("fs.file.impl.disable.cache", true);

        long count = 0;
        try (ParquetReader<GenericRecord> reader = AvroParquetReader
                .<GenericRecord>builder(hadoopPath)
                .withConf(conf)
                .build()) {
            while (reader.read() != null) {
                count++;
            }
        }
        return count;
    }

    // ==================== PRIVATE VALIDATION ====================

    private void validateUploadRole(DatasetType type) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new SecurityException("Authentification requise pour importer des données.");
        }

        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        boolean isAdmin = roles.contains("ROLE_ADMIN");
        boolean isManager = roles.contains("ROLE_MANAGER");

        log.info("Vérification des rôles pour type {} : {}", type, roles);

        switch (type) {
            case CLIENTS:
            case ACCOUNTS:
            case TRANSACTIONS_VAL:
                if (!isAdmin) {
                    throw new IllegalArgumentException("Seul un Administrateur peut importer ces types de données.");
                }
                break;
            case TRANSACTIONS_TRAIN:
            case TRANSACTIONS_TRAIN_ENRICHED:
                if (!isAdmin && !isManager) {
                    throw new IllegalArgumentException(
                            "Seul un Manager ou un Admin peut importer des données d'entraînement.");
                }
                break;
            default:
                throw new IllegalArgumentException("Type de dataset non reconnu.");
        }
    }

    private void validateCsvColumns(DatasetType type, Path csvPath) throws IOException {
        Map<String, List<String>> requiredMap = REQUIRED_COLUMNS_BY_TYPE.get(type);
        if (requiredMap == null)
            return;

        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader)) {

            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                throw new IllegalArgumentException("Le fichier CSV est vide ou ne contient pas d'en-tête.");
            }

            Set<String> actualHeaders = headerMap.keySet();
            List<String> missingColumns = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry : requiredMap.entrySet()) {
                String standardKey = entry.getKey();
                List<String> aliases = entry.getValue();

                boolean found = false;
                for (String alias : aliases) {
                    if (actualHeaders.stream().anyMatch(h -> h.equalsIgnoreCase(alias))) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    missingColumns.add(standardKey + " (p.ex: " + aliases.get(0) + ")");
                }
            }

            if (!missingColumns.isEmpty()) {
                String typeLabel = switch (type) {
                    case TRANSACTIONS_TRAIN -> "Transactions d'entraînement";
                    case TRANSACTIONS_VAL -> "Transactions à traiter (Validation)";
                    case CLIENTS -> "Clients";
                    case ACCOUNTS -> "Comptes";
                    default -> type.toString();
                };

                throw new IllegalArgumentException(
                        "Le fichier ne correspond pas au type sélectionné.\n" +
                                "Les colonnes obligatoires manquantes dans ce type \"" + typeLabel + "\" : " +
                                String.join(", ", missingColumns));
            }
        } catch (IllegalArgumentException e) {
            throw e; // Renvoyer les erreurs de validation
        } catch (Exception e) {
            log.error("Erreur technique lors de la validation des colonnes : ", e);
            throw new RuntimeException("Échec de la validation structurelle du fichier.");
        }
    }
}