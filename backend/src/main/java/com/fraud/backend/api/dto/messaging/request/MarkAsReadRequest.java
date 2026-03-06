package com.fraud.backend.api.dto.messaging.request;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class MarkAsReadRequest {
    private List<UUID> messageUuids;
    private boolean markAll;
}