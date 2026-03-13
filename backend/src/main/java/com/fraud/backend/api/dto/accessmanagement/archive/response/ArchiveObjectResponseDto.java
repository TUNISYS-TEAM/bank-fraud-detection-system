package com.fraud.backend.api.dto.accessmanagement.archive.response;

import com.fraud.backend.data.enums.Archive.ArchiveFormat;
import com.fraud.backend.data.enums.Archive.ArchiveStatus;
import com.fraud.backend.data.enums.Archive.ArchiveType;

import lombok.*;

import java.time.LocalDateTime;

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