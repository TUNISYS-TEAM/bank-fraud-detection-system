package com.fraud.backend.data.service.corebanking;

import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.enums.TransactionChannel;
import com.fraud.backend.data.enums.TransactionStatus;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public Page<Transaction> getAll(Pageable pageable) {
        return transactionRepository.findAll(pageable);
    }

    public Transaction getById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Transaction non trouvée: " + id));
    }

    public Transaction getByUuid(UUID uuid) {
        return transactionRepository.findByTransactionUuid(uuid)
                .orElseThrow(() -> new EntityNotFoundException("Transaction non trouvée: " + uuid));
    }

    public Page<Transaction> getByAccountId(Long accountId, Pageable pageable) {
        return transactionRepository.findByAccountId(accountId, pageable);
    }

    public Page<Transaction> getFraudulent(Pageable pageable) {
        return transactionRepository.findByIsFraudTrue(pageable);
    }

    public Page<Transaction> getRecentByAccount(Long accountId, LocalDateTime since, Pageable pageable) {
        return transactionRepository.findByAccountIdAndTransactionDateAfter(accountId, since, pageable);
    }

    public Page<Transaction> search(String status, String channel,
            LocalDateTime start, LocalDateTime end,
            Pageable pageable) {
        TransactionStatus s = null;
        if (status != null && !status.isBlank()) {
            try {
                s = TransactionStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Statut invalide ignoré dans la recherche: {}", status);
            }
        }

        TransactionChannel c = null;
        if (channel != null && !channel.isBlank()) {
            try {
                c = TransactionChannel.valueOf(channel.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Canal invalide ignoré dans la recherche: {}", channel);
            }
        }

        return transactionRepository.search(s, c, start, end, pageable);
    }

    public List<String> getAllStatuses() {
        return transactionRepository.findDistinctStatuses().stream()
                .map(Enum::name)
                .toList();
    }

    public List<String> getAllChannels() {
        return transactionRepository.findDistinctChannels().stream()
                .map(Enum::name)
                .toList();
    }
}