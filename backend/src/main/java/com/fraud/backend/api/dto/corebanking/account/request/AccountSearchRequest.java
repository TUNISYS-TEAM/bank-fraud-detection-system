package com.fraud.backend.api.dto.corebanking.account.request;

import lombok.Data;

@Data
public class AccountSearchRequest {
    private Long clientId;
    private String riskLevel;
    private Boolean activeOnly;
    private int page = 0;
    private int size = 20;
}