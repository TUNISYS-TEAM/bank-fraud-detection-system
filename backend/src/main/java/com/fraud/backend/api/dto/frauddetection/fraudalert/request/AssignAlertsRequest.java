package com.fraud.backend.api.dto.frauddetection.fraudalert.request;

import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class AssignAlertsRequest {
    private List<UUID> alertUuids;
    private Long analystId;
}