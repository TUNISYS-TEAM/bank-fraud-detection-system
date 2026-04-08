package com.fraud.backend.api.dto.frauddetection.fraudalert;

import com.fraud.backend.api.dto.frauddetection.fraudalert.response.FraudAlertResponse;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FraudAlertMapper {
    
    public FraudAlertResponse toResponse(FraudAlert alert) {
        if (alert == null) {
            return null;
        }
        
        FraudAlertResponse.FraudAlertResponseBuilder builder = FraudAlertResponse.builder()
            .id(alert.getId())
            .alertUuid(alert.getAlertUuid())
            .fraudScore(alert.getFraudScore())
            .supervisedScore(alert.getSupervisedScore())
            .anomalyScore(alert.getAnomalyScore())
            .riskLevel(alert.getRiskLevel())
            .alertType(alert.getAlertType())
            .status(alert.getStatus())
            .reviewComment(alert.getReviewComment())
            .actionTaken(alert.getActionTaken())
            .featuresSnapshot(alert.getFeaturesSnapshot())
            .createdAt(alert.getCreatedAt())
            .updatedAt(alert.getUpdatedAt());
        
        if (alert.getReviewDate() != null) {
            builder.reviewDate(alert.getReviewDate());
        }
        
        if (alert.getTransaction() != null) {
            builder.transactionId(alert.getTransaction().getId());
            // Use correct field names from Transaction entity
            builder.transactionAmount(alert.getTransaction().getAmount() != null ? 
                alert.getTransaction().getAmount().doubleValue() : null);
            builder.transactionCurrency(alert.getTransaction().getCurrency());
            builder.transactionChannel(alert.getTransaction().getChannel() != null ? 
                alert.getTransaction().getChannel().toString() : null);
            builder.transactionCategory(alert.getTransaction().getMerchantCategory());
            builder.fraudReason(alert.getTransaction().getFraudReason());
            
            if (alert.getTransaction().getAccount() != null) {
                builder.accountNumber(alert.getTransaction().getAccount().getAccountNumber());
                if (alert.getTransaction().getAccount().getClient() != null) {
                    builder.clientName(alert.getTransaction().getAccount().getClient().getFullName());
                }
            }
            
            if (alert.getTransaction().getDataset() != null && alert.getTransaction().getDataset().getAssignedManager() != null) {
                builder.assignedManagerName(alert.getTransaction().getDataset().getAssignedManager().getFirstName() + " " + alert.getTransaction().getDataset().getAssignedManager().getLastName());
            }
        }
        
        if (alert.getUser() != null) {
            builder.userId(alert.getUser().getId());
        }
        
        if (alert.getModelUsed() != null) {
            builder.modelUsedId(alert.getModelUsed().getId());
        }
        
        if (alert.getReviewedBy() != null) {
            builder.reviewedBy(alert.getReviewedBy().getId());
            builder.reviewedByName(alert.getReviewedBy().getFirstName() + " " + alert.getReviewedBy().getLastName());
        }
        
        return builder.build();
    }
    
    public List<FraudAlertResponse> toResponseList(List<FraudAlert> alerts) {
        if (alerts == null) {
            return null;
        }
        return alerts.stream()
            .map(this::toResponse)
            .toList();
    }
}