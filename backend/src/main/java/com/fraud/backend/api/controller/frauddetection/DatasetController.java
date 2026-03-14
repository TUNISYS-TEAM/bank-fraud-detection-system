package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetCleanResponse;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;
import com.fraud.backend.data.service.frauddetection.DatasetService;
import com.fraud.backend.api.dto.frauddetection.dataset.DatasetMapper;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetPreviewResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;
    private final DatasetMapper datasetMapper;

    @GetMapping
    public Page<DatasetResponse> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) DatasetStatus status,
            @RequestParam(value = "type", required = false) DatasetType type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "importedAt"));
        return datasetService.list(q, status, type, pageable).map(datasetMapper::toResponse);
    }

    @GetMapping("/{uuid}")
    public DatasetResponse get(@PathVariable UUID uuid) {
        return datasetMapper.toResponse(datasetService.get(uuid));
    }

    /**
     * Returns paginated TRANSACTIONS_TRAIN datasets with status ACTIVE or
     * PROCESSED.
     * Used by the model training pipeline to select a training dataset.
     * GET /api/datasets/training?page=0&size=10&q=search
     */
    @GetMapping("/training")
    public Page<DatasetResponse> listTrainingDatasets(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {

        log.info("Récupération paginée des datasets d'entraînement - page: {}, size: {}, search: {}", page, size, q);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "importedAt"));
        return datasetService.listTrainingDatasets(q, pageable).map(datasetMapper::toResponse);
    }

    @GetMapping("/training/all")
    public Page<DatasetResponse> listAllTrainingDatasets(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {

        log.info("Récupération paginée de TOUS les datasets d'entraînement - page: {}, size: {}, search: {}", page,
                size, q);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "importedAt"));
        return datasetService.listAllTrainingDatasets(q, pageable).map(datasetMapper::toResponse);
    }

    @GetMapping("/{uuid}/preview")
    public DatasetPreviewResponse preview(
            @PathVariable UUID uuid,
            @RequestParam(value = "limit", defaultValue = "10") int limit) throws IOException {
        return datasetService.preview(uuid, limit);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatasetResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("datasetName") String datasetName,
            @RequestPart(value = "type", required = false) String type) throws IOException {

        DatasetType datasetType = (type != null && !type.isBlank())
                ? DatasetType.valueOf(type.toUpperCase())
                : DatasetType.TRANSACTIONS_TRAIN;

        log.info("Uploading dataset: {} with type: {}", datasetName, datasetType);
        return datasetMapper.toResponse(datasetService.uploadDataset(datasetName, datasetType, file));
    }

    @DeleteMapping("/{uuid}")
    public void delete(@PathVariable UUID uuid) {
        datasetService.delete(uuid);
    }

    @PutMapping("/{uuid}")
    public DatasetResponse update(
            @PathVariable UUID uuid,
            @RequestBody DatasetUpdateRequest request) {
        return datasetMapper.toResponse(datasetService.update(uuid, request));
    }

    // ==================== ANALYSE DU DATASET ====================

    @PostMapping("/{uuid}/analyze")
    public ResponseEntity<DatasetAnalysisResponse> analyzeDataset(@PathVariable UUID uuid) throws Exception {
        log.info("Requête d'analyse pour dataset: {}", uuid);
        DatasetAnalysisResponse response = datasetService.analyzeDataset(uuid);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{uuid}/analysis")
    public ResponseEntity<DatasetAnalysisResponse> getAnalysis(@PathVariable UUID uuid) {
        log.info("Récupération de l'analyse pour dataset: {}", uuid);
        DatasetAnalysisResponse response = datasetService.getAnalysis(uuid);
        return ResponseEntity.ok(response);
    }

    // ==================== NETTOYAGE DU DATASET ====================

    @PostMapping("/{uuid}/clean")
    public ResponseEntity<DatasetCleanResponse> cleanDataset(@PathVariable UUID uuid) throws Exception {
        log.info("Requête de nettoyage pour dataset: {}", uuid);
        DatasetCleanResponse response = datasetService.cleanDataset(uuid);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{uuid}/clean/status")
    public ResponseEntity<DatasetCleanResponse> getCleaningStatus(@PathVariable UUID uuid) {
        log.info("Récupération du statut de nettoyage pour dataset: {}", uuid);
        DatasetCleanResponse response = datasetService.getCleaningStatus(uuid);
        return ResponseEntity.ok(response);
    }

    // ==================== DOWNLOAD ====================

    @GetMapping("/{uuid}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID uuid) {
        DatasetResponse ds = get(uuid);
        InputStream is = datasetService.download(uuid);
        InputStreamResource resource = new InputStreamResource(is);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + ds.getDatasetName() + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(resource);
    }

    // ==================== STATISTIQUES ADMIN ====================

    @GetMapping("/count")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Long> count(@RequestParam(required = false) List<DatasetType> types) {
        long count = (types != null && !types.isEmpty()) ? datasetService.count(types) : datasetService.count();
        return ResponseEntity.ok(count);
    }

    @GetMapping("/total-size")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BigDecimal> getTotalSize(@RequestParam(required = false) List<DatasetType> types) {
        BigDecimal totalSize = datasetService.getTotalSize(types);
        return ResponseEntity.ok(totalSize);
    }
}