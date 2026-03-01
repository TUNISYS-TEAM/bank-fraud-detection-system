package com.fraud.backend.api.dto.corebanking.client.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class ClientResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private String fullName;
    private LocalDate dateOfBirth;
    private int accountCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}