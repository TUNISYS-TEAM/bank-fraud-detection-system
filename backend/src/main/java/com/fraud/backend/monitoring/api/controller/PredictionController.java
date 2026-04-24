package com.fraud.backend.monitoring.api.controller;

import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.service.frauddetection.alert.PostalCodeCoordinatesResolver;
import com.fraud.backend.monitoring.api.dto.BehaviorStatsResponse;
import com.fraud.backend.monitoring.api.dto.TrainingDataAssembler;
import com.fraud.backend.monitoring.api.dto.TrainingDataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class PredictionController {

    private static final int MAX_WINDOW_DAYS = 365;

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final PostalCodeCoordinatesResolver coordinatesResolver;

    @GetMapping("/training-data")
    public ResponseEntity<TrainingDataResponse> getTrainingData(
            @RequestParam(defaultValue = "90") int windowDays) {

        if (windowDays <= 0 || windowDays > MAX_WINDOW_DAYS) {
            log.warn("Invalid training data windowDays={}", windowDays);
            return ResponseEntity.badRequest().build();
        }

        LocalDateTime endDate = LocalDateTime.now();
        LocalDateTime startDate = endDate.minusDays(windowDays);

        List<Transaction> transactions = transactionRepository.findAllByTransactionDateBetween(startDate, endDate);
        List<FraudAlert> alerts = fraudAlertRepository.findByCreatedAtBetween(startDate, endDate);

        transactions.sort(Comparator.comparing(Transaction::getTransactionDate));

        TrainingDataResponse response = TrainingDataAssembler.assemble(transactions, alerts, coordinatesResolver,
                startDate, endDate);

        log.info("Training data served: {} transactions for window {}-{}",
                transactions.size(), startDate, endDate);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/behavior-stats")
    public ResponseEntity<BehaviorStatsResponse> getBehaviorStats(
            @RequestParam String postalCode,
            @RequestParam(defaultValue = "7") int windowDays) {

        if (windowDays <= 0 || windowDays > MAX_WINDOW_DAYS) {
            return ResponseEntity.badRequest().build();
        }

        LocalDateTime endDate = LocalDateTime.now();
        LocalDateTime startDate = endDate.minusDays(windowDays);

        List<Transaction> transactions = transactionRepository.findAllByPostalCodeClientAndTransactionDateBetween(
                postalCode, startDate, endDate);

        // Extraire les IDs clients uniques
        Function<Transaction, Long> clientIdExtractor =
                tx -> tx.getAccount().getClient().getClientId();

        long totalCustomers = transactions.stream()
                .filter(tx -> tx.getAccount() != null && tx.getAccount().getClient() != null)
                .map(clientIdExtractor)
                .distinct()
                .count();

        long highRiskCustomers = transactions.stream()
                .filter(tx -> tx.getFraudScore() != null && tx.getFraudScore().doubleValue() >= 0.7)
                .filter(tx -> tx.getAccount() != null && tx.getAccount().getClient() != null)
                .map(clientIdExtractor)
                .distinct()
                .count();

        double highRiskRatio = totalCustomers > 0
                ? (double) highRiskCustomers / totalCustomers
                : 0.0;

        double avgFraudScore = transactions.stream()
                .filter(tx -> tx.getFraudScore() != null)
                .mapToDouble(tx -> tx.getFraudScore().doubleValue())
                .average()
                .orElse(0.0);

        BehaviorStatsResponse response = BehaviorStatsResponse.builder()
                .driftScore(Math.min(avgFraudScore, 1.0))
                .highRiskRatio(highRiskRatio)
                .build();

        log.info("Behavior stats for postalCode={}: driftScore={}, highRiskRatio={}",
                postalCode, response.getDriftScore(), response.getHighRiskRatio());

        return ResponseEntity.ok(response);
    }
}