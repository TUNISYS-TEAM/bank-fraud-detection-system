package com.fraud.backend.api.dto.corebanking.transaction;

import com.fraud.backend.api.dto.corebanking.transaction.response.TransactionResponse;
import com.fraud.backend.api.dto.corebanking.transaction.response.TransactionSummaryResponse;
import com.fraud.backend.data.entity.corebanking.Transaction;
import org.springframework.stereotype.Component;

@Component
public class TransactionMapper {

    public TransactionResponse toResponse(Transaction transaction) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .transactionUuid(transaction.getTransactionUuid())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .transactionDate(transaction.getTransactionDate())
                .merchantCategory(transaction.getMerchantCategory())
                .channel(transaction.getChannel() != null ? transaction.getChannel().name() : null)
                .status(transaction.getStatus() != null ? transaction.getStatus().name() : null)
                .postalCodeMerchant(transaction.getPostalCodeMerchant())
                .postalCodeClient(transaction.getPostalCodeClient())
                .isFraud(transaction.getIsFraud())
                .fraudScore(transaction.getFraudScore())
                .fraudReason(transaction.getFraudReason())
                .ipAddress(transaction.getIpAddress())
                .deviceId(transaction.getDeviceId())
                .accountId(transaction.getAccount() != null ? transaction.getAccount().getId() : null)
                .accountNumber(transaction.getAccount() != null ? transaction.getAccount().getAccountNumber() : null)
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }

    public TransactionSummaryResponse toSummaryResponse(Transaction transaction) {
        return TransactionSummaryResponse.builder()
                .id(transaction.getId())
                .transactionUuid(transaction.getTransactionUuid())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .transactionDate(transaction.getTransactionDate())
                .channel(transaction.getChannel() != null ? transaction.getChannel().name() : null)
                .status(transaction.getStatus() != null ? transaction.getStatus().name() : null)
                .isFraud(transaction.getIsFraud())
                .fraudScore(transaction.getFraudScore())
                .accountNumber(transaction.getAccount() != null ? transaction.getAccount().getAccountNumber() : null)
                .build();
    }
}