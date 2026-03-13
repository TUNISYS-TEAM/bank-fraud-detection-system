package com.fraud.backend.api.dto.accessmanagement.archive.request;

import com.fraud.backend.data.enums.Archive.ArchiveStatus;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArchiveObjectStatusUpdateDto {

    @NotNull
    private ArchiveStatus status;
}