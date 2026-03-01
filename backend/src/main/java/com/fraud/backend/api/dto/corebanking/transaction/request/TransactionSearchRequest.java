package com.fraud.backend.api.dto.corebanking.transaction.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TransactionSearchRequest {
    private Long accountId;
    private String status;
    private String channel;
    private Boolean fraudOnly;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private int page = 0;
    private int size = 20;
}