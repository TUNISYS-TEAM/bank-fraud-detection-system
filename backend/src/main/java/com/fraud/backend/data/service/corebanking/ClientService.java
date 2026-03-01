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
        return clientRepository.findWithAccountsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Client non trouvé: " + id));
    }

    public Page<Client> searchByName(String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return clientRepository.findAll(pageable);
        }
        return clientRepository.searchByName(search.trim(), pageable);
    }

    public long getAccountCount(Long clientId) {
        return accountRepository.countByClientId(clientId);
    }
}