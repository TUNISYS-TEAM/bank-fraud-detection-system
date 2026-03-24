package com.fraud.backend.data.service.corebanking;

import com.fraud.backend.data.entity.corebanking.Client;
import com.fraud.backend.data.repository.corebanking.AccountRepository;
import com.fraud.backend.data.repository.corebanking.ClientRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClientService {

    private final ClientRepository clientRepository;
    private final AccountRepository accountRepository;

    public Page<Client> getAll(Pageable pageable) {
        return clientRepository.findAll(pageable);
    }

    public Client getById(Long id) {
        return clientRepository.findWithAccountsByClientId(id)
                .orElseThrow(() -> new EntityNotFoundException("Client non trouvé: " + id));
    }

    public Page<Client> searchByName(String search, Integer accountCount, Pageable pageable) {
        String searchQuery = (search == null) ? "" : search.trim();
        return clientRepository.searchClients(searchQuery, accountCount, pageable);
    }

    public long getAccountCount(Long clientId) {
        return accountRepository.countByClientId(clientId);
    }

    public Map<String, Long> getAccountDistribution() {
        Map<String, Long> distribution = new java.util.HashMap<>();
        distribution.put("1 compte", 0L);
        distribution.put("2 comptes", 0L);
        distribution.put("3+ comptes", 0L);

        clientRepository.getRawAccountCounts().forEach(row -> {
            int count = ((Number) row[0]).intValue();
            long clientCount = ((Number) row[1]).longValue();
            
            if (count == 1) {
                distribution.put("1 compte", clientCount);
            } else if (count == 2) {
                distribution.put("2 comptes", clientCount);
            } else if (count > 2) {
                distribution.put("3+ comptes", distribution.get("3+ comptes") + clientCount);
            }
        });
        
        return distribution;
    }

    public Map<String, Long> getAgeDistribution() {
        return clientRepository.getAgeDistribution().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
    }
}