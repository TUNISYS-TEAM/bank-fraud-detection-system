package com.fraud.backend.api.dto.accessmanagement.archive.request;

import com.fraud.backend.data.enums.Archive.ArchiveFormat;
import com.fraud.backend.data.enums.Archive.ArchiveType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchiveObjectCreateRequestDto {

    @NotNull
    private ArchiveType type;

    @NotNull
    private LocalDateTime startAt;

    @NotNull
    private LocalDateTime endAt;

    @NotBlank
    private String bucket;

    @NotBlank
    private String objectKey;

    private ArchiveFormat format;

}