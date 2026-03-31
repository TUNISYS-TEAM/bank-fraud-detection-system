package com.fraud.backend.data.service.corebanking;

import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.enums.transactions.TransactionChannel;
import com.fraud.backend.data.enums.transactions.TransactionStatus;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;

    public Page<Transaction> getAll(Pageable pageable) {
        return transactionRepository.findAll(pageable);
    }

    public Transaction getById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Transaction non trouvée: " + id));
    }

    public Transaction getByUuid(UUID uuid) {
        return transactionRepository.findByTransactionUuid(uuid)
                .orElseThrow(() -> new EntityNotFoundException("Transaction non trouvée: " + uuid));
    }

    public Page<Transaction> getByAccountId(Long accountId, Pageable pageable) {
        return transactionRepository.findByAccountId(accountId, pageable);
    }

    public Page<Transaction> getFraudulent(Pageable pageable) {
        return transactionRepository.findByIsFraudTrue(pageable);
    }

    public Page<Transaction> getAnalyzed(Pageable pageable) {
        return transactionRepository.findByFraudScoreIsNotNull(pageable);
    }

    public Page<Transaction> getRecentByAccount(Long accountId, LocalDateTime since, Pageable pageable) {
        return transactionRepository.findByAccountIdAndTransactionDateAfter(accountId, since, pageable);
    }

    public Page<Transaction> search(String status, String channel,
            LocalDateTime start, LocalDateTime end,
            Pageable pageable) {
        // Optimisation: si tout est null, findAll direct
        if ((status == null || status.isBlank()) && (channel == null || channel.isBlank()) && start == null && end == null) {
            return transactionRepository.findAll(pageable);
        }

        TransactionStatus s = null;
        if (status != null && !status.isBlank()) {
            try {
                s = TransactionStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Statut invalide ignoré dans la recherche: {}", status);
            }
        }

        TransactionChannel c = null;
        if (channel != null && !channel.isBlank()) {
            try {
                c = TransactionChannel.valueOf(channel.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Canal invalide ignoré dans la recherche: {}", channel);
            }
        }

        return transactionRepository.search(s, c, start, end, pageable);
    }

    public List<String> getAllStatuses() {
        return transactionRepository.findDistinctStatuses().stream()
                .map(Enum::name)
                .toList();
    }

    public List<String> getAllChannels() {
        return transactionRepository.findDistinctChannels().stream()
                .map(Enum::name)
                .toList();
    }

    public List<java.util.Map<String, Object>> getTimeSeriesStats(LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            // Détection dynamique de la date la plus récente
            start = transactionRepository.findMaxTransactionDate()
                    .orElse(LocalDateTime.now())
                    .withMinute(0).withSecond(0).withNano(0);
            end = start.plusHours(1); // On prend jusqu'à la fin de cette heure
            start = start.minusHours(23); // Fenêtre de 24h (23h précédentes + heure actuelle)
        }
        
        if (end == null) {
            end = start.plusHours(24);
        }

        log.info("Fetching hourly stats for dynamic window: {} to {}", start, end);
        log.info("Total transactions in database: {}", transactionRepository.count());

        List<Object[]> results = transactionRepository.getTimeSeriesStats(start, end);
        log.info("Query returned {} rows", results.size());

        java.util.Map<String, java.util.Map<String, Object>> dataByHour = new java.util.HashMap<>();
        java.time.format.DateTimeFormatter keyFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH");

        for (Object[] row : results) {
            if (row[0] == null) continue;
            
            String rawDate = row[0].toString().replace("T", " ");
            String hourKey = rawDate.length() >= 13 ? rawDate.substring(0, 13) : rawDate;
            
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("date", row[0].toString());
            data.put("total", row[1] != null ? ((Number) row[1]).longValue() : 0L);
            data.put("fraudes", row[2] != null ? ((Number) row[2]).longValue() : 0L);
            dataByHour.put(hourKey, data);
            
            if (((Number)row[1]).longValue() > 0) {
                log.info("Found data for hour {}: total={}, frauds={}", hourKey, row[1], row[2]);
            }
        }

        List<java.util.Map<String, Object>> finalResults = new java.util.ArrayList<>();
        LocalDateTime current = start;
        while (!current.isAfter(end) && finalResults.size() < 25) { // Limite à 24-25 points
            String hourKey = current.format(keyFormatter);
            
            if (dataByHour.containsKey(hourKey)) {
                finalResults.add(dataByHour.get(hourKey));
            } else {
                java.util.Map<String, Object> emptyData = new java.util.HashMap<>();
                emptyData.put("date", current.toString());
                emptyData.put("total", 0L);
                emptyData.put("fraudes", 0L);
                finalResults.add(emptyData);
            }
            current = current.plusHours(1);
        }

        return finalResults;
    }

    public java.util.Map<String, Object> getGlobalAnalyzedStats() {
        long totalAnalyzed = transactionRepository.countByFraudScoreIsNotNull();
        long criticalAlerts = fraudAlertRepository.countByRiskLevel("CRITICAL"); 
        long totalAlerts = fraudAlertRepository.count();
        Double avgScore = transactionRepository.getAverageFraudScore().orElse(0.0);
        
        // Nouvelle répartition par canal
        List<Object[]> channelStats = transactionRepository.countByChannel();
        java.util.Map<String, Long> channelMap = new java.util.HashMap<>();
        for (Object[] row : channelStats) {
            if (row[0] != null) {
                channelMap.put(row[0].toString(), ((Number) row[1]).longValue());
            }
        }

        // Nouvelle exposition financière
        List<Object[]> amountStatsList = transactionRepository.sumAmountByRiskScore();
        double[] amountByRisk = new double[4];
        if (amountStatsList != null && !amountStatsList.isEmpty()) {
            Object[] amountStats = amountStatsList.get(0);
            if (amountStats != null && amountStats.length >= 4) {
                amountByRisk[0] = amountStats[0] != null ? ((Number) amountStats[0]).doubleValue() : 0.0 ;
                amountByRisk[1] = amountStats[1] != null ? ((Number) amountStats[1]).doubleValue() : 0.0 ;
                amountByRisk[2] = amountStats[2] != null ? ((Number) amountStats[2]).doubleValue() : 0.0 ;
                amountByRisk[3] = amountStats[3] != null ? ((Number) amountStats[3]).doubleValue() : 0.0 ;
            }
        }

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalAnalyzed", totalAnalyzed);
        stats.put("alerts", totalAlerts);
        stats.put("criticalAlerts", criticalAlerts);
        stats.put("avgRiskScore", avgScore);
        stats.put("channelDistribution", channelMap);
        stats.put("amountExposure", amountByRisk);
        
        return stats;
    }
}