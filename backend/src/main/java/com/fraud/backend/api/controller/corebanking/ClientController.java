package com.fraud.backend.api.controller.corebanking;

import com.fraud.backend.api.dto.corebanking.client.ClientMapper;
import com.fraud.backend.api.dto.corebanking.client.response.ClientResponse;
import com.fraud.backend.api.dto.corebanking.client.response.ClientSummaryResponse;
import com.fraud.backend.data.service.corebanking.ClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;
    private final ClientMapper clientMapper;

    @GetMapping
    public ResponseEntity<Page<ClientSummaryResponse>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("clientId").ascending());
        return ResponseEntity.ok(
                clientService.getAll(pageable).map(client -> clientMapper.toSummaryResponse(client,
                        clientService.getAccountCount(client.getClientId()))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClientResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(clientMapper.toResponse(clientService.getById(id)));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<ClientSummaryResponse>> search(
            @RequestParam String search,
            @RequestParam(required = false) Integer accountCount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("clientId").ascending());
        return ResponseEntity.ok(
                clientService.searchByName(search, accountCount, pageable)
                        .map(client -> clientMapper.toSummaryResponse(client,
                                clientService.getAccountCount(client.getClientId()))));
    }

    @GetMapping("/count")
    public ResponseEntity<Long> getCount() {
        return ResponseEntity.ok(clientService.searchByName("", null, PageRequest.of(0, 1)).getTotalElements());
    }

    @GetMapping("/stats/account-distribution")
    public ResponseEntity<Map<String, Long>> getAccountDistribution() {
        return ResponseEntity.ok(clientService.getAccountDistribution());
    }

    @GetMapping("/stats/age-distribution")
    public ResponseEntity<Map<String, Long>> getAgeDistribution() {
        return ResponseEntity.ok(clientService.getAgeDistribution());
    }
}