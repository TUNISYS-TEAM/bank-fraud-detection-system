package com.fraud.backend.api.dto.corebanking.client.response;

import com.fraud.backend.api.dto.corebanking.account.response.AccountSummaryResponse;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ClientResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private String fullName;
    private LocalDate dateOfBirth;
    private String nationalId;
    private int accountCount;
    private List<AccountSummaryResponse> accounts;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}