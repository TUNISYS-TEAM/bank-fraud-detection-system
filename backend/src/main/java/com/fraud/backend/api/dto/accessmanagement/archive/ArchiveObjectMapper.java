package com.fraud.backend.api.dto.accessmanagement.archive;

import com.fraud.backend.api.dto.accessmanagement.archive.request.ArchiveObjectCreateRequestDto;
import com.fraud.backend.api.dto.accessmanagement.archive.response.ArchiveObjectResponseDto;
import com.fraud.backend.data.entity.accessmanagement.ArchiveObject;
import com.fraud.backend.data.enums.archive.ArchiveFormat;
import com.fraud.backend.data.enums.archive.ArchiveStatus;

public final class ArchiveObjectMapper {

    private ArchiveObjectMapper() {
    }

    public static ArchiveObjectResponseDto toDto(ArchiveObject ao) {
        return ArchiveObjectResponseDto.builder()
                .id(ao.getId())
                .type(ao.getType())
                .startAt(ao.getStartAt())
                .endAt(ao.getEndAt())
                .bucket(ao.getBucket())
                .objectKey(ao.getObjectKey())
                .format(ao.getFormat())
                .status(ao.getStatus())
                .createdAt(ao.getCreatedAt())
                .updatedAt(ao.getUpdatedAt())
                .build();
    }

    public static ArchiveObject toEntity(ArchiveObjectCreateRequestDto req) {
        ArchiveObject ao = new ArchiveObject();
        ao.setType(req.getType());
        ao.setStartAt(req.getStartAt());
        ao.setEndAt(req.getEndAt());
        ao.setBucket(req.getBucket());
        ao.setObjectKey(req.getObjectKey());
        ao.setFormat(req.getFormat() != null ? req.getFormat() : ArchiveFormat.JSONL_GZ);
        ao.setStatus(ArchiveStatus.PENDING);
        return ao;
    }
}