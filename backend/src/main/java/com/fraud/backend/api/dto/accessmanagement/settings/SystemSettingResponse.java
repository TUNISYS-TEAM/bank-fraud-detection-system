package com.fraud.backend.api.dto.accessmanagement.settings;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class SystemSettingResponse {
    private String key;
    private String value;
    private String description;
    private LocalDateTime updatedAt;
}