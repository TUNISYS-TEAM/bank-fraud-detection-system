package com.fraud.backend.api.dto.accessmanagement.archive.response;

import lombok.*;

import java.time.LocalDateTime;

import com.fraud.backend.data.enums.archive.ArchiveFormat;
import com.fraud.backend.data.enums.archive.ArchiveStatus;
import com.fraud.backend.data.enums.archive.ArchiveType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchiveObjectResponseDto {
    private Long id;

    private ArchiveType type;
    private LocalDateTime startAt;
    private LocalDateTime endAt;

    private String bucket;
    private String objectKey;

    private ArchiveFormat format;

    private ArchiveStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}