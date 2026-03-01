package com.fraud.backend.api.dto.corebanking.account.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AccountResponse {
    private Long id;
    private String accountNumber;
    private String accountType;
    private BigDecimal balance;
    private String currency;
    private Boolean isActive;
    private LocalDateTime closedDate;
    private String riskLevel;
    private Long clientId;
    private String clientFullName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}