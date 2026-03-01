package com.fraud.backend.data.service.corebanking;

import com.fraud.backend.data.entity.corebanking.Account;
import com.fraud.backend.data.repository.corebanking.AccountRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;

    public Page<Account> getAll(Pageable pageable) {
        return accountRepository.findAll(pageable);
    }

    public Account getById(Long id) {
        return accountRepository.findByIdWithClient(id)
                .orElseThrow(() -> new EntityNotFoundException("Compte non trouvé: " + id));
    }

    public Account getByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumberWithClient(accountNumber)
                .orElseThrow(() -> new EntityNotFoundException("Compte non trouvé: " + accountNumber));
    }

    public List<Account> getByClientId(Long clientId) {
        return accountRepository.findByClientId(clientId);
    }

    public Page<Account> getByRiskLevel(String riskLevel, Pageable pageable) {
        return accountRepository.findByRiskLevel(riskLevel, pageable);
    }

    public Page<Account> getActiveAccounts(Pageable pageable) {
        return accountRepository.findByIsActiveTrue(pageable);
    }

    public Page<Account> getActiveAccountsByClient(Long clientId, Pageable pageable) {
        return accountRepository.findByClientIdAndIsActiveTrue(clientId, pageable);
    }

}