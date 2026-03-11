package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.data.enums.DatasetStatus;
import com.fraud.backend.api.dto.frauddetection.dataset.request.DatasetUpdateRequest;
import com.fraud.backend.data.service.frauddetection.DatasetService;
import com.fraud.backend.api.dto.frauddetection.dataset.DatasetMapper;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetPreviewResponse;
import com.fraud.backend.api.dto.frauddetection.dataset.response.DatasetAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

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
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "importedAt"));
        return datasetService.list(q, status, pageable).map(datasetMapper::toResponse);
    }

    @GetMapping("/{uuid}")
    public DatasetResponse get(@PathVariable UUID uuid) {
        return datasetMapper.toResponse(datasetService.get(uuid));
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
            @RequestPart("datasetName") String datasetName) throws IOException {
        return datasetMapper.toResponse(datasetService.uploadTransactionsCsv(datasetName, file));
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

    // Analyse du dataset
    @PostMapping("/{uuid}/analyze")
    public ResponseEntity<DatasetAnalysisResponse> analyzeDataset(@PathVariable UUID uuid) throws Exception {
        DatasetAnalysisResponse response = datasetService.analyzeDataset(uuid);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{uuid}/analysis")
    public ResponseEntity<DatasetAnalysisResponse> getAnalysis(@PathVariable UUID uuid) {
        DatasetAnalysisResponse response = datasetService.getAnalysis(uuid);
        return ResponseEntity.ok(response);
    }
}