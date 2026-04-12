package com.fraud.backend.api.dto.frauddetection.fraudalert.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FraudLocationResponse {
    private String postalCode;
    private long fraudCount;
    private double lat;
    private double lon;
}