package com.fraud.backend.monitoring.api.controller;

import com.fraud.backend.monitoring.api.dto.TrainingDataAssembler;
import com.fraud.backend.monitoring.api.dto.TrainingDataResponse;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.FraudAlert;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.service.frauddetection.alert.PostalCodeCoordinatesResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class PredictionController {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final PostalCodeCoordinatesResolver coordinatesResolver;

    @GetMapping("/training-data")
    public ResponseEntity<TrainingDataResponse> getTrainingData(
            @RequestParam(defaultValue = "90") int windowDays) {

        LocalDateTime endDate = LocalDateTime.now();
        LocalDateTime startDate = endDate.minusDays(windowDays);

        List<Transaction> transactions = transactionRepository.findAllByTransactionDateBetween(startDate, endDate);
        List<FraudAlert> alerts = fraudAlertRepository.findByCreatedAtBetween(startDate, endDate);

        TrainingDataResponse response = TrainingDataAssembler.assemble(transactions, alerts, coordinatesResolver);
        return ResponseEntity.ok(response);
    }
}