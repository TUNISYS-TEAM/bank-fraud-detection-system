package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetCleanResponse;
import com.fraud.backend.data.enums.datasets.DatasetStatus;
import com.fraud.backend.data.enums.datasets.DatasetType;
import com.fraud.backend.data.service.frauddetection.analysis.DatasetService;
import com.fraud.backend.api.dto.frauddetection.dataset.DatasetMapper;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetPreviewResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fraud.backend.data.entity.frauddetection.Dataset;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            @RequestParam(value = "types", required = false) List<DatasetType> types,
            @RequestParam(value = "visibility", required = false) Boolean visibility,
            @RequestParam(value = "qualified", required = false) Boolean qualified,
            @RequestParam(value = "managerId", required = false) Long managerId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "importedAt"));
        return datasetService.list(q, status, type, types, visibility, qualified, managerId, pageable)
                .map(datasetMapper::toResponse);
    }

    @GetMapping("/{uuid}")
    public DatasetResponse get(@PathVariable UUID uuid) {
        return datasetMapper.toResponse(datasetService.get(uuid));
    }

    @GetMapping("/training")
    public Page<DatasetResponse> listTrainingDatasets(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "managerId", required = false) Long managerId,
            @RequestParam(value = "visibility", required = false) Boolean visibility,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {

        log.info("Récupération paginée des datasets d'entraînement - page: {}, size: {}, search: {}, manager: {}, visibility: {}", 
                page, size, q, managerId, visibility);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "importedAt"));
        return datasetService.listTrainingDatasets(q, managerId, visibility, pageable).map(datasetMapper::toResponse);
    }

    @GetMapping("/training/all")
    public Page<DatasetResponse> listAllTrainingDatasets(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "managerId", required = false) Long managerId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {

        log.info("Récupération paginée de TOUS les datasets d'entraînement - page: {}, size: {}, search: {}, manager: {}", page,
                size, q, managerId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "importedAt"));
        return datasetService.listAllTrainingDatasets(q, managerId, pageable).map(datasetMapper::toResponse);
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

    @GetMapping("/{uuid}/is-enriched")
    public ResponseEntity<Boolean> checkIfEnriched(@PathVariable UUID uuid) {
        return ResponseEntity.ok(datasetService.checkIfEnriched(uuid));
    }

    @DeleteMapping("/{uuid}")
    public void delete(@PathVariable UUID uuid) {
        datasetService.delete(uuid);
    }

    @PutMapping("/{uuid}")
    public ResponseEntity<?> update(
            @PathVariable UUID uuid,
            @RequestBody DatasetUpdateRequest request) {
        try {
            return ResponseEntity.ok(datasetMapper.toResponse(datasetService.update(uuid, request)));
        } catch (IllegalArgumentException e) {
            Map<String, Object> body = new HashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.ok(body);
        }
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
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Long> count(@RequestParam(value = "types", required = false) List<DatasetType> types,
                                      @RequestParam(value = "managerId", required = false) Long managerId) {
        long count = datasetService.count(types, managerId);
        return ResponseEntity.ok(count);
    }

    @GetMapping("/total-size")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<BigDecimal> getTotalSize(@RequestParam(value = "types", required = false) List<DatasetType> types,
                                                   @RequestParam(value = "managerId", required = false) Long managerId) {
        BigDecimal totalSize = datasetService.getTotalSize(types, managerId);
        return ResponseEntity.ok(totalSize);
    }

    @GetMapping("/latest-import")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<LocalDateTime> getLatestImportDate(@RequestParam(value = "types", required = false) List<DatasetType> types,
                                                             @RequestParam(value = "managerId", required = false) Long managerId) {
        LocalDateTime latestImport = datasetService.getLatestImportDate(types, managerId);
        return ResponseEntity.ok(latestImport);
    }

    // ==================== VISIBILITY CONTROLS ====================

    @PostMapping("/{uuid}/activate")
    public ResponseEntity<?> activate(@PathVariable UUID uuid) {
        try {
            datasetService.activate(uuid);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    @PostMapping("/{uuid}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID uuid) {
        log.info("Désactivation du dataset: {}", uuid);
        datasetService.deactivate(uuid);
        return ResponseEntity.ok().build();
    }

    // ==================== EXTRACTION COMPORTEMENT ====================

    @PostMapping("/{uuid}/extract-behavior")
    public ResponseEntity<DatasetCleanResponse> extractBehavior(@PathVariable UUID uuid) throws Exception {
        log.info("Demande d'extraction des features comportementales pour dataset: {}", uuid);
        DatasetCleanResponse response = datasetService.extractBehavior(uuid);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{uuid}/clone-for-training")
    public ResponseEntity<DatasetResponse> cloneForTraining(@PathVariable UUID uuid) throws Exception {
        log.info("Demande d'import du dataset de validation {} pour entraînement", uuid);
        Dataset cloned = datasetService.cloneForTraining(uuid);
        return ResponseEntity.ok(datasetMapper.toResponse(cloned));
    }
    @PostMapping("/{uuid}/qualify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void qualify(@PathVariable UUID uuid) {
        datasetService.qualifyDataset(uuid);
    }
}