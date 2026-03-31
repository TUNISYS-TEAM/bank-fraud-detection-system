package com.fraud.backend.api.controller.corebanking;

import com.fraud.backend.api.dto.corebanking.transaction.TransactionMapper;
import com.fraud.backend.api.dto.corebanking.transaction.response.TransactionResponse;
import com.fraud.backend.api.dto.corebanking.transaction.response.TransactionSummaryResponse;
import com.fraud.backend.data.service.corebanking.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final TransactionMapper transactionMapper;

    @GetMapping
    public ResponseEntity<Page<TransactionSummaryResponse>> getAllOrSearch(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        return ResponseEntity.ok(
                transactionService.search(status, channel, start, end, pageable)
                        .map(transactionMapper::toSummaryResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(transactionMapper.toResponse(transactionService.getById(id)));
    }

    @GetMapping("/uuid/{uuid}")
    public ResponseEntity<TransactionResponse> getByUuid(@PathVariable UUID uuid) {
        return ResponseEntity.ok(transactionMapper.toResponse(transactionService.getByUuid(uuid)));
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<Page<TransactionSummaryResponse>> getByAccountId(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        return ResponseEntity.ok(
                transactionService.getByAccountId(accountId, pageable).map(transactionMapper::toSummaryResponse));
    }

    @GetMapping("/fraud")
    public ResponseEntity<Page<TransactionSummaryResponse>> getFraudulent(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        return ResponseEntity.ok(
                transactionService.getFraudulent(pageable).map(transactionMapper::toSummaryResponse));
    }

    @GetMapping("/analyzed")
    public ResponseEntity<Page<TransactionSummaryResponse>> getAnalyzed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        return ResponseEntity.ok(
                transactionService.getAnalyzed(pageable).map(transactionMapper::toSummaryResponse));
    }

    @GetMapping("/meta/statuses")
    public ResponseEntity<java.util.List<String>> getAllStatuses() {
        return ResponseEntity.ok(transactionService.getAllStatuses());
    }

    @GetMapping("/meta/channels")
    public ResponseEntity<java.util.List<String>> getAllChannels() {
        return ResponseEntity.ok(transactionService.getAllChannels());
    }

    @GetMapping("/stats/time-series")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> getTimeSeriesStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResponseEntity.ok(transactionService.getTimeSeriesStats(start, end));
    }

    @GetMapping("/stats/analyzed-global")
    public ResponseEntity<java.util.Map<String, Object>> getGlobalAnalyzedStats() {
        return ResponseEntity.ok(transactionService.getGlobalAnalyzedStats());
    }
}