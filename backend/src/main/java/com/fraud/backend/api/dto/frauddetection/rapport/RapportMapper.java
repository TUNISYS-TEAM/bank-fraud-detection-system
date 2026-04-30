package com.fraud.backend.api.dto.frauddetection.rapport;

import com.fraud.backend.api.dto.frauddetection.rapport.response.RapportRecipientResponse;
import com.fraud.backend.api.dto.frauddetection.rapport.response.RapportResponse;
import com.fraud.backend.data.entity.frauddetection.Rapport;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.Role;

@Component
public class RapportMapper {

    private final AppUserRepository appUserRepository;

    public RapportMapper(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    public RapportResponse toResponse(Rapport rapport) {
        if (rapport == null) return null;

        return RapportResponse.builder()
                .id(rapport.getId())
                .rapportUuid(rapport.getRapportUuid())
                .title(rapport.getTitle())
                .type(rapport.getType())
                .format(rapport.getFormat())
                .description(rapport.getDescription())
                .status(rapport.getStatus())
                .startDate(rapport.getStartDate())
                .endDate(rapport.getEndDate())
                .totalTransactions(rapport.getTotalTransactions())
                .fraudDetected(rapport.getFraudDetected())
                .avgFraudScore(rapport.getAvgFraudScore())
                .dataSummary(rapport.getDataSummary())
                .createdAt(rapport.getCreatedAt())
                .generatedById(rapport.getGeneratedBy() != null ? rapport.getGeneratedBy().getId() : null)
                .generatedByRole(rapport.getGeneratedBy() != null && rapport.getGeneratedBy().getRole() != null ? rapport.getGeneratedBy().getRole().getName() : null)
                .generatedByName(rapport.getGeneratedBy() != null ? 
                        rapport.getGeneratedBy().getFirstName() + " " + rapport.getGeneratedBy().getLastName() : "Système")
                .generatedByEmail(rapport.getGeneratedBy() != null ? rapport.getGeneratedBy().getEmail() : null)
                .recipients(mapRecipients(rapport.getDistributionList()))
                .build();
    }

    private List<RapportRecipientResponse> mapRecipients(List<Map<String, Object>> distributionList) {
        if (distributionList == null) return List.of();
        return distributionList.stream()
                .map(map -> {
                    Long userId = map.get("userId") != null ? Long.valueOf(map.get("userId").toString()) : null;
                    String name = "Inconnu";
                    String role = "Collaborateur";
                    
                    if (userId != null) {
                        var userOpt = appUserRepository.findById(userId);
                        if (userOpt.isPresent()) {
                            AppUser u = userOpt.get();
                            name = u.getFirstName() + " " + u.getLastName();
                            Role r = u.getRole();
                            if (r != null) {
                                role = r.getName();
                            }
                        }
                    }

                    // Robust boolean mapping for isRead
                    boolean isReadValue = false;
                    Object isReadObj = map.get("isRead");
                    if (isReadObj instanceof Boolean) {
                        isReadValue = (Boolean) isReadObj;
                    } else if (isReadObj != null) {
                        isReadValue = Boolean.parseBoolean(isReadObj.toString());
                    }

                    return RapportRecipientResponse.builder()
                            .recipientId(userId)
                            .recipientName(name)
                            .recipientRole(role)
                            .isRead(isReadValue)
                            .readAt(map.get("readAt") != null ? LocalDateTime.parse(map.get("readAt").toString()) : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    public RapportResponse toResponse(Rapport rapport, Long currentUserId) {
        RapportResponse response = toResponse(rapport);
        if (response != null && rapport.getDistributionList() != null) {
            boolean isRead = rapport.getDistributionList().stream()
                    .filter(r -> r.get("userId") != null && String.valueOf(r.get("userId")).equals(String.valueOf(currentUserId)))
                    .map(r -> {
                        Object isReadObj = r.get("isRead");
                        if (isReadObj instanceof Boolean) return (Boolean) isReadObj;
                        if (isReadObj != null) return Boolean.parseBoolean(isReadObj.toString());
                        return false;
                    })
                    .findFirst()
                    .orElse(false);
            response.setRead(isRead);
        }
        return response;
    }

    public List<RapportResponse> toResponseList(List<Rapport> rapports) {
        return rapports.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<RapportResponse> toResponseList(List<Rapport> rapports, Long currentUserId) {
        return rapports.stream().map(r -> toResponse(r, currentUserId)).collect(Collectors.toList());
    }
}