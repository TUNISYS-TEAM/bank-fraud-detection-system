package com.fraud.backend.api.dto.corebanking.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fraud.backend.api.dto.corebanking.transaction.response.TransactionResponse;
import com.fraud.backend.api.dto.corebanking.transaction.response.TransactionSummaryResponse;
import com.fraud.backend.data.entity.corebanking.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TransactionMapper {

    private final ObjectMapper objectMapper;

    public TransactionResponse toResponse(Transaction transaction) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .transactionUuid(transaction.getTransactionUuid())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .transactionDate(transaction.getTransactionDate())
                .merchantCategory(transaction.getMerchantCategory())
                .channel(transaction.getChannel() != null ? transaction.getChannel().name() : null)
                .status(transaction.getStatus() != null ? transaction.getStatus().name() : null)
                .postalCodeMerchant(transaction.getPostalCodeMerchant())
                .postalCodeClient(transaction.getPostalCodeClient())
                .isFraud(transaction.getIsFraud())
                .fraudScore(transaction.getFraudScore())
                .riskLevel(transaction.getFraudReason() != null && transaction.getFraudReason().has("risk_level") ? 
                        transaction.getFraudReason().get("risk_level").asText().toUpperCase() : "UNKNOWN")
                .fraudReason(transaction.getFraudReason())  // Directly pass JsonNode
                .ipAddress(transaction.getIpAddress())
                .deviceId(transaction.getDeviceId())
                .accountId(transaction.getAccount() != null ? transaction.getAccount().getId() : null)
                .accountNumber(transaction.getAccount() != null ? transaction.getAccount().getAccountNumber() : null)
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }

    public TransactionSummaryResponse toSummaryResponse(Transaction transaction) {
        JsonNode fraudReasonNode = transaction.getFraudReason();
        String riskLevel = "UNKNOWN";
        JsonNode summaryFraudReason = null;
        
        if (fraudReasonNode != null) {
            // 1. Extract risk level for the dedicated field
            if (fraudReasonNode.has("risk_level")) {
                riskLevel = fraudReasonNode.get("risk_level").asText().toUpperCase();
            }

            // 2. Create simplified JSON for the summary view
            JsonNode summaryNode = fraudReasonNode.get("summary");
            if (summaryNode != null && summaryNode.isTextual()) {
                ObjectNode minimalNode = objectMapper.createObjectNode();
                minimalNode.set("summary", summaryNode);
                minimalNode.put("risk_level", riskLevel);
                summaryFraudReason = minimalNode;
            } else {
                summaryFraudReason = fraudReasonNode;
            }
        }

        return TransactionSummaryResponse.builder()
                .id(transaction.getId())
                .transactionUuid(transaction.getTransactionUuid())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .transactionDate(transaction.getTransactionDate())
                .channel(transaction.getChannel() != null ? transaction.getChannel().name() : null)
                .status(transaction.getStatus() != null ? transaction.getStatus().name() : null)
                .isFraud(transaction.getIsFraud())
                .fraudScore(transaction.getFraudScore())
                .riskLevel(riskLevel)
                .fraudReason(summaryFraudReason)
                .accountNumber(transaction.getAccount() != null ? transaction.getAccount().getAccountNumber() : null)
                .clientName(transaction.getAccount() != null && transaction.getAccount().getClient() != null ? 
                        transaction.getAccount().getClient().getFullName() : "Unknown Client")
                .build();
    }

    /**
     * Helper method to extract the summary text from fraud reason
     */
    public String extractFraudReasonSummaryText(Transaction transaction) {
        JsonNode fraudReasonNode = transaction.getFraudReason();
        if (fraudReasonNode == null) {
            return null;
        }
        
        JsonNode summaryNode = fraudReasonNode.get("summary");
        if (summaryNode != null && summaryNode.isTextual()) {
            return summaryNode.textValue();
        }
        
        // Fallback to primary factor if available
        JsonNode primaryFactorNode = fraudReasonNode.get("primary_factor_display");
        if (primaryFactorNode != null && primaryFactorNode.isTextual()) {
            return "Risk: " + primaryFactorNode.textValue();
        }
        
        return null;
    }

    /**
     * Helper method to get the full structured fraud reason
     */
    public JsonNode getStructuredFraudReason(Transaction transaction) {
        return transaction.getFraudReason();
    }

    /**
     * Helper method to check if transaction has detailed explanation
     */
    public boolean hasDetailedExplanation(Transaction transaction) {
        JsonNode fraudReasonNode = transaction.getFraudReason();
        if (fraudReasonNode == null) {
            return false;
        }
        
        JsonNode contributionsNode = fraudReasonNode.get("contributions");
        return contributionsNode != null && contributionsNode.isArray() && contributionsNode.size() > 0;
    }

    /**
     * Helper method to get the primary factor display name
     */
    public String getPrimaryFactorDisplay(Transaction transaction) {
        JsonNode fraudReasonNode = transaction.getFraudReason();
        if (fraudReasonNode == null) {
            return null;
        }
        
        JsonNode primaryFactorNode = fraudReasonNode.get("primary_factor_display");
        if (primaryFactorNode != null && primaryFactorNode.isTextual()) {
            return primaryFactorNode.textValue();
        }
        
        JsonNode primaryFactorNodeAlt = fraudReasonNode.get("primary_factor");
        if (primaryFactorNodeAlt != null && primaryFactorNodeAlt.isTextual()) {
            return primaryFactorNodeAlt.textValue();
        }
        
        return null;
    }

    /**
     * Helper method to get total risk points
     */
    public Integer getTotalRiskPoints(Transaction transaction) {
        JsonNode fraudReasonNode = transaction.getFraudReason();
        if (fraudReasonNode == null) {
            return null;
        }
        
        JsonNode totalRiskNode = fraudReasonNode.get("total_risk_points");
        if (totalRiskNode != null && totalRiskNode.isNumber()) {
            return totalRiskNode.intValue();
        }
        
        return null;
    }

    /**
     * Helper method to get contributions list
     */
    public JsonNode getContributions(Transaction transaction) {
        JsonNode fraudReasonNode = transaction.getFraudReason();
        if (fraudReasonNode == null) {
            return null;
        }
        
        return fraudReasonNode.get("contributions");
    }
}