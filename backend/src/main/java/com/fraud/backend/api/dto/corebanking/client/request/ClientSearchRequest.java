package com.fraud.backend.api.dto.corebanking.client.request;

import lombok.Data;

@Data
public class ClientSearchRequest {
    private String search;
    private int page = 0;
    private int size = 20;
}