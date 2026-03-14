package com.fraud.backend.api.dto.corebanking.account;

import com.fraud.backend.api.dto.corebanking.account.response.AccountResponse;
import com.fraud.backend.api.dto.corebanking.account.response.AccountSummaryResponse;
import com.fraud.backend.data.entity.corebanking.Account;
import org.springframework.stereotype.Component;

@Component
public class AccountMapper {

    public AccountResponse toResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .accountType(account.getAccountType() != null ? account.getAccountType().name() : null)
                .balance(account.getBalance())
                .currency(account.getCurrency())
                .isActive(account.getIsActive())
                .closedDate(account.getClosedDate())
                .riskLevel(account.getRiskLevel())
                .clientId(account.getClient() != null ? account.getClient().getClientId() : null)
                .clientFullName(account.getClient() != null ? account.getClient().getFullName() : null)
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    public AccountSummaryResponse toSummaryResponse(Account account) {
        return AccountSummaryResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .accountType(account.getAccountType() != null ? account.getAccountType().name() : null)
                .balance(account.getBalance())
                .currency(account.getCurrency())
                .isActive(account.getIsActive())
                .riskLevel(account.getRiskLevel())
                .build();
    }
}