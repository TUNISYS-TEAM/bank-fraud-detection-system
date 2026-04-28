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
    private final com.fraud.backend.data.service.accessmanagement.UserService userService;

    private Long getEffectiveManagerId() {
        try {
            com.fraud.backend.data.entity.accessmanagement.AppUser user = userService.getCurrentUser();
            if (user == null) return null;
            
            String role = user.getRole().getName().toUpperCase();
            if ("ADMIN".equals(role)) return null;
            if ("MANAGER".equals(role)) return user.getId();
            if ("ANALYST".equals(role) && user.getManager() != null) return user.getManager().getId();
        } catch (Exception e) {
            log.warn("Could not determine effective manager for transaction filter: {}", e.getMessage());
        }
        return null;
    }

    public Page<Transaction> getAll(Pageable pageable) {
        Long managerId = getEffectiveManagerId();
        return transactionRepository.findAllFiltered(managerId, pageable);
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
        Long managerId = getEffectiveManagerId();
        return transactionRepository.findByIsFraudTrueFiltered(managerId, pageable);
    }

    public Page<Transaction> getAnalyzed(UUID datasetUuid, Pageable pageable) {
        Long managerId = getEffectiveManagerId();
        return transactionRepository.findAnalyzedFiltered(managerId, datasetUuid, pageable);
    }

    public Page<Transaction> getRecentByAccount(Long accountId, LocalDateTime since, Pageable pageable) {
        return transactionRepository.findByAccountIdAndTransactionDateAfter(accountId, since, pageable);
    }

    public Page<Transaction> search(String status, String channel,
            LocalDateTime start, LocalDateTime end,
            Pageable pageable) {
        
        if ((status == null || status.isBlank()) &&
            (channel == null || channel.isBlank()) &&
            start == null && end == null) {
            return getAll(pageable); // Bypasses complex bug JPQL query for global counts
        }
            
        Long managerId = getEffectiveManagerId();
        
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

        return transactionRepository.searchFiltered(s, null, c, start, end, managerId, pageable);
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

    public List<java.util.Map<String, Object>> getTimeSeriesStats(LocalDateTime start, LocalDateTime end, String datasetUuid) {
        if (start == null) {
            start = transactionRepository.findMaxTransactionDate()
                    .orElse(LocalDateTime.now())
                    .withMinute(0).withSecond(0).withNano(0);
            end = start.plusHours(1);
            start = start.minusHours(23);
        }
        
        if (end == null) {
            end = start.plusHours(24);
        }

        Long managerId = getEffectiveManagerId();
        if ("all".equals(datasetUuid) || datasetUuid != null && datasetUuid.isEmpty()) datasetUuid = null;
        List<Object[]> results = transactionRepository.getTimeSeriesStatsFiltered(start, end, managerId, datasetUuid);

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
        }

        List<java.util.Map<String, Object>> finalResults = new java.util.ArrayList<>();
        LocalDateTime current = start;
        while (!current.isAfter(end) && finalResults.size() < 25) {
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

    public java.util.Map<String, Object> getGlobalAnalyzedStats(String datasetUuid) {
        Long managerId = getEffectiveManagerId();
        if ("all".equals(datasetUuid) || datasetUuid != null && datasetUuid.isEmpty()) datasetUuid = null;
        
        long totalAnalyzed = transactionRepository.countGlobalAnalyzedFiltered(managerId, datasetUuid);
        
        List<Object[]> alertStats = fraudAlertRepository.getAlertStatsFiltered(managerId, datasetUuid);
        long totalAlerts = 0;
        long criticalAlerts = 0;
        if (alertStats != null && !alertStats.isEmpty() && alertStats.get(0) != null) {
            Object[] stats = alertStats.get(0);
            totalAlerts = stats[0] != null ? ((Number) stats[0]).longValue() : 0L;
            criticalAlerts = stats[1] != null ? ((Number) stats[1]).longValue() : 0L;
        }
        
        Double avgScore = transactionRepository.getAverageFraudScoreFiltered(managerId, datasetUuid).orElse(0.0);
        List<Object[]> channelStats = transactionRepository.countByChannelFiltered(managerId, datasetUuid);
        List<Object[]> amountStatsList = transactionRepository.sumAmountByRiskScoreFiltered(managerId, datasetUuid);
        List<Object[]> riskCountList = transactionRepository.countByRiskLevelFiltered(managerId, datasetUuid);
        
        java.util.Map<String, Long> channelMap = new java.util.HashMap<>();
        for (Object[] row : channelStats) {
            if (row[0] != null) {
                channelMap.put(row[0].toString(), ((Number) row[1]).longValue());
            }
        }

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

        long[] riskCounts = new long[4];
        if (riskCountList != null && !riskCountList.isEmpty()) {
            Object[] counts = riskCountList.get(0);
            if (counts != null && counts.length >= 4) {
                riskCounts[0] = counts[0] != null ? ((Number) counts[0]).longValue() : 0L;
                riskCounts[1] = counts[1] != null ? ((Number) counts[1]).longValue() : 0L;
                riskCounts[2] = counts[2] != null ? ((Number) counts[2]).longValue() : 0L;
                riskCounts[3] = counts[3] != null ? ((Number) counts[3]).longValue() : 0L;
            }
        }

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalAnalyzed", totalAnalyzed);
        stats.put("alerts", totalAlerts);
        stats.put("criticalAlerts", criticalAlerts);
        stats.put("avgRiskScore", avgScore);
        stats.put("channelDistribution", channelMap);
        stats.put("amountExposure", amountByRisk);
        stats.put("riskDistribution", riskCounts); // FIX: Added riskDistribution so frontend chart works
        return stats;
    }

    /**
     * Calcule le profil de risque global basé sur les transactions.
     * Ces statistiques sont globales et ne dépendent pas des filtres utilisateur.
     */
    public java.util.Map<String, Object> getRiskProfile() {
        java.util.Map<String, Object> profile = new java.util.HashMap<>();

        // 1. Canal le plus risqué
        List<Object[]> channels = transactionRepository.fraudCountByChannel();
        if (!channels.isEmpty()) {
            Object[] top = channels.get(0);
            long total = ((Number) top[1]).longValue();
            long frauds = ((Number) top[2]).longValue();
            profile.put("riskiestChannel", java.util.Map.of(
                    "name", top[0].toString(),
                    "totalCount", total,
                    "fraudCount", frauds,
                    "fraudRate", total > 0 ? (double) frauds / total * 100 : 0));
        }

        // 2. Catégorie la plus risquée
        List<Object[]> categories = transactionRepository.fraudCountByCategory();
        if (!categories.isEmpty()) {
            Object[] top = categories.get(0);
            long total = ((Number) top[1]).longValue();
            long frauds = ((Number) top[2]).longValue();
            profile.put("riskiestCategory", java.util.Map.of(
                    "name", top[0].toString(),
                    "totalCount", total,
                    "fraudCount", frauds,
                    "fraudRate", total > 0 ? (double) frauds / total * 100 : 0));
        }

        // 3. Heure la plus risquée
        List<Object[]> hours = transactionRepository.fraudCountByHour();
        if (!hours.isEmpty()) {
            Object[] top = hours.get(0);
            int hour = ((Number) top[0]).intValue();
            long total = ((Number) top[1]).longValue();
            long frauds = ((Number) top[2]).longValue();
            profile.put("riskiestHour", java.util.Map.of(
                    "hour", hour,
                    "label", String.format("%02d:00 - %02d:59", hour, hour),
                    "totalCount", total,
                    "fraudCount", frauds,
                    "fraudRate", total > 0 ? (double) frauds / total * 100 : 0));
        }

        // 4. Type de compte le plus risqué
        List<Object[]> accountTypes = transactionRepository.fraudCountByAccountType();
        if (!accountTypes.isEmpty()) {
            Object[] top = accountTypes.get(0);
            long total = ((Number) top[1]).longValue();
            long frauds = ((Number) top[2]).longValue();
            profile.put("riskiestAccountType", java.util.Map.of(
                    "name", top[0].toString(),
                    "totalCount", total,
                    "fraudCount", frauds,
                    "fraudRate", total > 0 ? (double) frauds / total * 100 : 0));
        }

        return profile;
    }
}