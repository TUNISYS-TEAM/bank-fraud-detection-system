package com.fraud.backend.api.dto.corebanking.transaction.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class TransactionResponse {
    private Long id;
    private UUID transactionUuid;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime transactionDate;
    private String merchantCategory;
    private String channel;
    private String status;
    private String postalCodeMerchant;
    private String postalCodeClient;
    private Boolean isFraud;
    private BigDecimal fraudScore;
    private JsonNode fraudReason;  
    private String ipAddress;
    private String deviceId;
    private Long accountId;
    private String accountNumber;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}