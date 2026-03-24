package com.fraud.backend.api.controller.corebanking;

import java.math.BigDecimal;

import com.fraud.backend.api.dto.corebanking.account.AccountMapper;
import com.fraud.backend.api.dto.corebanking.account.response.AccountResponse;
import com.fraud.backend.api.dto.corebanking.account.response.AccountSummaryResponse;
import com.fraud.backend.data.service.corebanking.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

        private final AccountService accountService;
        private final AccountMapper accountMapper;

        @GetMapping
        public ResponseEntity<Page<AccountSummaryResponse>> getAll(
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {

                Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
                return ResponseEntity.ok(
                                accountService.getAll(pageable).map(accountMapper::toSummaryResponse));
        }

        @GetMapping("/{id}")
        public ResponseEntity<AccountResponse> getById(@PathVariable Long id) {
                return ResponseEntity.ok(accountMapper.toResponse(accountService.getById(id)));
        }

        @GetMapping("/number/{accountNumber}")
        public ResponseEntity<AccountResponse> getByAccountNumber(@PathVariable String accountNumber) {
                return ResponseEntity.ok(accountMapper.toResponse(accountService.getByAccountNumber(accountNumber)));
        }

        @GetMapping("/client/{clientId}")
        public ResponseEntity<List<AccountSummaryResponse>> getByClientId(@PathVariable Long clientId) {
                return ResponseEntity.ok(
                                accountService.getByClientId(clientId).stream()
                                                .map(accountMapper::toSummaryResponse)
                                                .toList());
        }

        @GetMapping("/risk/{riskLevel}")
        public ResponseEntity<Page<AccountSummaryResponse>> getByRiskLevel(
                        @PathVariable String riskLevel,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {

                Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
                return ResponseEntity.ok(
                                accountService.getByRiskLevel(riskLevel, pageable)
                                                .map(accountMapper::toSummaryResponse));
        }

        @GetMapping("/active")
        public ResponseEntity<Page<AccountSummaryResponse>> getActiveAccounts(
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {

                Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
                return ResponseEntity.ok(
                                accountService.getActiveAccounts(pageable).map(accountMapper::toSummaryResponse));
        }

        @GetMapping("/client/{clientId}/active")
        public ResponseEntity<Page<AccountSummaryResponse>> getActiveAccountsByClient(
                        @PathVariable Long clientId,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {
                Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
                return ResponseEntity.ok(
                                accountService.getActiveAccountsByClient(clientId, pageable)
                                                .map(accountMapper::toSummaryResponse));
        }
    @GetMapping("/count")
    public ResponseEntity<Long> getCount() {
        return ResponseEntity.ok(accountService.getTotalCount());
    }

    @GetMapping("/active/count")
    public ResponseEntity<Long> getActiveCount() {
        return ResponseEntity.ok(accountService.getActiveAccountCount());
    }

    @GetMapping("/balance/total")
    public ResponseEntity<BigDecimal> getTotalBalance() {
        return ResponseEntity.ok(accountService.getTotalBalance());
    }
}