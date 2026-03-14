package com.fraud.backend.api.dto.accessmanagement.archive.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

import com.fraud.backend.data.enums.archive.ArchiveFormat;
import com.fraud.backend.data.enums.archive.ArchiveType;

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