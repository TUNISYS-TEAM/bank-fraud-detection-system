package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.DatasetStatus;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetPreviewResponse;
import com.fraud.backend.data.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.nio.charset.StandardCharsets;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasetRepository;
    private final StorageService storageService;

    public Page<Dataset> list(String q, DatasetStatus status, Pageable pageable) {
        if (status != null) {
            return datasetRepository.findByStatus(status, pageable);
        }
        if (q != null && !q.isBlank()) {
            String searchTerm = q.trim();
            return datasetRepository.findByDatasetNameOrCode(searchTerm, pageable);
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

        // 1) créer dataset
        Dataset ds = Dataset.builder()
                .datasetName(datasetName.trim())
                .datasetCode(generateCode())
                .status(DatasetStatus.INACTIVE)
                .build();

        ds = datasetRepository.save(ds);

        // 2) key Data Lake
        String key = "raw/transactions/" + ds.getDatasetUuid() + "/original.csv";

        // 3) stats
        long rowCount = countCsvRows(file);
        BigDecimal sizeMb = bytesToMb(file.getSize());

        // 4) upload MinIO
        storageService.upload(
                key,
                file.getInputStream(),
                file.getSize(),
                file.getContentType() != null ? file.getContentType() : "text/csv");

        // 5) update metadata + save
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
    public Dataset update(UUID uuid,
            com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest request) {
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

    private long countCsvRows(MultipartFile file) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            long lines = br.lines().count();
            return Math.max(0, lines - 1); // header
        }
    }

    private BigDecimal bytesToMb(long bytes) {
        return BigDecimal.valueOf(bytes)
                .divide(BigDecimal.valueOf(1024.0 * 1024.0), 2, RoundingMode.HALF_UP);
    }

    private String generateCode() {
        return "DS-" + System.currentTimeMillis();
    }

    @Transactional(readOnly = true)
    public DatasetPreviewResponse preview(UUID uuid, int limit) throws IOException {

        Dataset ds = get(uuid);

        if (ds.getFilePath() == null || ds.getFilePath().isBlank()) {
            throw new IllegalStateException("Dataset has no file_path (not uploaded?)");
        }

        int safeLimit = Math.max(1, Math.min(limit, 50)); // protection (max 50 lignes)

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

                count++;
                if (count >= safeLimit)
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
}