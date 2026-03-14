package com.fraud.backend.api.dto.corebanking.client;

import com.fraud.backend.api.dto.corebanking.account.AccountMapper;
import com.fraud.backend.api.dto.corebanking.client.response.ClientResponse;
import com.fraud.backend.api.dto.corebanking.client.response.ClientSummaryResponse;
import com.fraud.backend.data.entity.corebanking.Client;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ClientMapper {

    private final AccountMapper accountMapper;

    public ClientResponse toResponse(Client client) {
        return ClientResponse.builder()
                .id(client.getClientId())
                .firstName(client.getFirstName())
                .lastName(client.getLastName())
                .fullName(client.getFullName())
                .dateOfBirth(client.getDateOfBirth())
                .nationalId(client.getNationalId())
                .accountCount(client.getAccounts() != null ? client.getAccounts().size() : 0)
                .accounts(client.getAccounts() != null ? 
                        client.getAccounts().stream().map(accountMapper::toSummaryResponse).collect(Collectors.toList()) : null)
                .createdAt(client.getCreatedAt())
                .updatedAt(client.getUpdatedAt())
                .build();
    }

    public ClientSummaryResponse toSummaryResponse(Client client, long accountCount) {
        return ClientSummaryResponse.builder()
                .id(client.getClientId())
                .firstName(client.getFirstName())
                .lastName(client.getLastName())
                .fullName(client.getFullName())
                .accountCount((int) accountCount)
                .build();
    }
}