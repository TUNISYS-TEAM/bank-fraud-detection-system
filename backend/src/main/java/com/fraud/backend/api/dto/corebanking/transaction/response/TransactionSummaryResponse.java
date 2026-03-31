package com.fraud.backend.api.dto.corebanking.transaction.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class TransactionSummaryResponse {
    private Long id;
    private UUID transactionUuid;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime transactionDate;
    private String channel;
    private String status;
    private Boolean isFraud;
    private BigDecimal fraudScore;
    private JsonNode fraudReason;  
    private String accountNumber;
    private String clientName;
}