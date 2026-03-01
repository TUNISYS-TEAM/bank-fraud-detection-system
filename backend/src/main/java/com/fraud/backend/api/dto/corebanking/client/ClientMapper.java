package com.fraud.backend.api.dto.corebanking.client;

import com.fraud.backend.api.dto.corebanking.client.response.ClientResponse;
import com.fraud.backend.api.dto.corebanking.client.response.ClientSummaryResponse;
import com.fraud.backend.data.entity.corebanking.Client;
import org.springframework.stereotype.Component;

@Component
public class ClientMapper {

    public ClientResponse toResponse(Client client) {
        return ClientResponse.builder()
                .id(client.getId())
                .firstName(client.getFirstName())
                .lastName(client.getLastName())
                .fullName(client.getFullName())
                .dateOfBirth(client.getDateOfBirth())
                .accountCount(client.getAccounts() != null ? client.getAccounts().size() : 0)
                .createdAt(client.getCreatedAt())
                .updatedAt(client.getUpdatedAt())
                .build();
    }

    public ClientSummaryResponse toSummaryResponse(Client client, long accountCount) {
        return ClientSummaryResponse.builder()
                .id(client.getId())
                .firstName(client.getFirstName())
                .lastName(client.getLastName())
                .fullName(client.getFullName())
                .accountCount((int) accountCount)
                .build();
    }
}