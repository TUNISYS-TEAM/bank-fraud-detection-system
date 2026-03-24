package com.fraud.backend.api.dto.frauddetection.model.request;

import lombok.Data;

import java.util.UUID;

@Data
public class DeployModelRequest {
    private UUID modelUuid;
    private String notes;
}