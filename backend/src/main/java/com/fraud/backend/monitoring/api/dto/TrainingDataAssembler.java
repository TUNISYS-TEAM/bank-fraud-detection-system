package com.fraud.backend.monitoring.api.dto;

import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.service.frauddetection.alert.PostalCodeCoordinatesResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TrainingDataAssembler {

    private TrainingDataAssembler() {
        // Utility class
    }

    public static TrainingDataResponse assemble(
            List<Transaction> transactions,
            List<FraudAlert> alerts,
            PostalCodeCoordinatesResolver coordinatesResolver,
            LocalDateTime windowStart,
            LocalDateTime windowEnd
    ) {
        Set<Long> fraudulentIds = alerts.stream()
                .filter(alert -> alert.getTransaction() != null)
                .map(alert -> alert.getTransaction().getId())
                .collect(Collectors.toSet());

        List<TrainingTransaction> list = transactions.stream()
                .map(transaction -> toTrainingTransaction(transaction, fraudulentIds, coordinatesResolver))
                .collect(Collectors.toList());

        return TrainingDataResponse.builder()
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .transactions(list)
                .build();
    }

    private static TrainingTransaction toTrainingTransaction(
            Transaction tx,
            Set<Long> fraudulentIds,
            PostalCodeCoordinatesResolver resolver
    ) {
        String postalCode = tx.getPostalCodeClient() != null
                ? tx.getPostalCodeClient()
                : "0000";

        double[] coords = safeResolveCoordinates(resolver, postalCode);

        return TrainingTransaction.builder()
                .transactionId(resolveTransactionId(tx))
                .timestamp(tx.getTransactionDate())
                .amount(tx.getAmount())
                .postalCode(postalCode)
                .latitude(coords[0])
                .longitude(coords[1])
                .customerId(resolveCustomerId(tx))
                .channel(tx.getChannel() != null ? tx.getChannel().name() : null)
                .isFraud(Boolean.TRUE.equals(tx.getIsFraud()) || fraudulentIds.contains(tx.getId()))
                .fraudScore(convertToDouble(tx.getFraudScore()))
                .transactionStatus(tx.getStatus() != null ? tx.getStatus().name() : null)
                .build();
    }

    private static String resolveTransactionId(Transaction tx) {
        if (tx.getTransactionUuid() != null) {
            return tx.getTransactionUuid().toString();
        }

        return tx.getId() != null ? String.valueOf(tx.getId()) : null;
    }

    private static String resolveCustomerId(Transaction tx) {
        if (tx.getAccount() == null || tx.getAccount().getClient() == null) {
            return null;
        }

        return tx.getAccount().getClient().getClientId() != null
                ? tx.getAccount().getClient().getClientId().toString()
                : null;
    }

    private static double[] safeResolveCoordinates(
            PostalCodeCoordinatesResolver resolver,
            String postalCode
    ) {
        try {
            double[] coords = resolver.getCoordinates(postalCode);

            if (coords != null && coords.length >= 2) {
                return coords;
            }
        } catch (Exception ignored) {
            // fallback below
        }

        return new double[]{0.0, 0.0};
    }

    private static Double convertToDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }
}