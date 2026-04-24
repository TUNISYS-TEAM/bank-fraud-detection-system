package com.fraud.backend.monitoring.api.dto;

import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.service.frauddetection.alert.PostalCodeCoordinatesResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TrainingDataAssembler {

    public static TrainingDataResponse assemble(List<Transaction> transactions,
                                                List<FraudAlert> alerts,
                                                PostalCodeCoordinatesResolver coordinatesResolver) {
        Set<Long> fraudulentIds = alerts.stream()
                .filter(a -> a.getTransaction() != null)
                .map(a -> a.getTransaction().getId())
                .collect(Collectors.toSet());

        Function<Transaction, TrainingTransaction> mapper = tx -> toTrainingTransaction(tx, fraudulentIds, coordinatesResolver);
        List<TrainingTransaction> list = transactions.stream()
                .map(mapper)
                .collect(Collectors.toList());

        LocalDateTime start = transactions.isEmpty() ? null : transactions.get(0).getTransactionDate();
        LocalDateTime end = transactions.isEmpty() ? null : transactions.get(transactions.size() - 1).getTransactionDate();

        return TrainingDataResponse.builder()
                .windowStart(start)
                .windowEnd(end)
                .transactions(list)
                .build();
    }

    private static TrainingTransaction toTrainingTransaction(Transaction tx,
                                                             Set<Long> fraudulentIds,
                                                             PostalCodeCoordinatesResolver resolver) {
        String postalCode = tx.getPostalCodeClient() != null ? tx.getPostalCodeClient() : "0000";
        double[] coords = resolver.getCoordinates(postalCode);
        return TrainingTransaction.builder()
                .transactionId(tx.getTransactionUuid().toString())
                .timestamp(tx.getTransactionDate())
                .amount(tx.getAmount())
                .postalCode(postalCode)
                .latitude(coords[0])
                .longitude(coords[1])
                .customerId(tx.getAccount() != null && tx.getAccount().getClient() != null
                        ? tx.getAccount().getClient().getClientId().toString()
                        : null)
                .channel(tx.getChannel() != null ? tx.getChannel().name() : null)
                .isFraud(Boolean.TRUE.equals(tx.getIsFraud()) || fraudulentIds.contains(tx.getId()))
                .fraudScore(convertToDouble(tx.getFraudScore()))
                .transactionStatus(tx.getStatus() != null ? tx.getStatus().name() : null)
                .build();
    }

    private static Double convertToDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }
}