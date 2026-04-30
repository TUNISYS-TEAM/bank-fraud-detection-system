package com.fraud.backend.data.service.frauddetection.reporting;

import com.fraud.backend.api.dto.frauddetection.rapport.request.GenerateRapportRequest;
import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.frauddetection.Dataset;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.entity.frauddetection.Rapport;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.event.NotificationEvent;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import com.fraud.backend.data.repository.corebanking.TransactionRepository;
import com.fraud.backend.data.repository.frauddetection.DatasetRepository;
import com.fraud.backend.data.repository.frauddetection.FraudAlertRepository;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import com.fraud.backend.data.repository.frauddetection.RapportRepository;
import com.fraud.backend.data.service.notification.NotificationEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RapportService {

    private final RapportRepository rapportRepository;
    private final AppUserRepository appUserRepository;
    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final ModelMLRepository modelMLRepository;
    private final DatasetRepository datasetRepository;
    private final KeycloakMappingRepository keycloakMappingRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationEventProducer notificationEventProducer;

    private String getKeycloakIdForUser(Long userId) {
        return keycloakMappingRepository.findByUserId(userId)
                .map(KeycloakMapping::getKeycloakId)
                .orElse(null);
    }

    private void notifyNewRapport(Rapport rapport, List<Long> recipientIds) {
        if (recipientIds == null || recipientIds.isEmpty()) return;
        
        try {
            for (Long recipientId : recipientIds) {
                String receiverKeycloakId = getKeycloakIdForUser(recipientId);
                if (receiverKeycloakId != null) {
                    // Send new report event
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("type", "NEW_REPORT");
                    payload.put("rapportUuid", rapport.getRapportUuid().toString());
                    payload.put("title", rapport.getTitle());
                    payload.put("message", "Un nouveau rapport \"" + rapport.getTitle() + "\" a été généré pour vous.");
                    payload.put("generatedByName", rapport.getGeneratedBy().getFirstName() + " " + rapport.getGeneratedBy().getLastName());
                    payload.put("createdAt", rapport.getCreatedAt().toString());

                    messagingTemplate.convertAndSendToUser(
                            receiverKeycloakId,
                            "/queue/messages",
                            Map.of("type", "NEW_REPORT", "payload", payload));

                    // Update unread count
                    long unreadCount = rapportRepository.countUnreadRapports(recipientId);
                    messagingTemplate.convertAndSendToUser(
                            receiverKeycloakId,
                            "/queue/unread-reports",
                            unreadCount);

                    // 2. Send Kafka event for persistence
                    AppUser recipient = appUserRepository.findById(recipientId).orElse(null);
                    if (recipient != null) {
                        NotificationEvent event = NotificationEvent.builder()
                                .eventId(UUID.randomUUID().toString())
                                .eventType("NEW_REPORT")
                                .userId(recipientId)
                                .userEmail(recipient.getEmail())
                                .payload(payload)
                                .timestamp(java.time.Instant.now())
                                .build();
                        notificationEventProducer.sendNotification(event);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to send WebSocket notification for rapport", e);
        }
    }

    private AppUser findUserByKeycloakId(String keycloakId) {
        return keycloakMappingRepository.findByKeycloakId(keycloakId)
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
    }

    @Transactional
    public Rapport initiateRapport(GenerateRapportRequest request, String userKeycloakId) {
        AppUser user = findUserByKeycloakId(userKeycloakId);

        LocalDateTime start = request.getStartDate();
        LocalDateTime end = request.getEndDate();

        // Déterminer les dates selon le type si non fournies
        if (start == null || end == null) {
            end = LocalDateTime.now();
            switch (request.getType()) {
                case "DAILY":
                    start = end.minusDays(1);
                    break;
                case "WEEKLY":
                    start = end.minusWeeks(1);
                    break;
                case "MONTHLY":
                    start = end.minusMonths(1);
                    break;
                default:
                    start = end.minusDays(1);
            }
        }

        Rapport rapport = Rapport.builder()
                .title(request.getTitle())
                .type(request.getType())
                .description(request.getDescription())
                .startDate(start)
                .endDate(end)
                .generatedBy(user)
                .status("GENERATING")
                .format("JSON")
                .build();

        final Rapport savedRapport = rapportRepository.save(rapport);

        // Ajouter les destinataires dans le champ JSONB
        if (request.getRecipientIds() != null && !request.getRecipientIds().isEmpty()) {
            List<Map<String, Object>> recipients = request.getRecipientIds().stream()
                    .map(id -> {
                        Map<String, Object> r = new HashMap<>();
                        r.put("userId", id);
                        r.put("isRead", false);
                        r.put("readAt", null);
                        return r;
                    })
                    .collect(Collectors.toList());
            savedRapport.setDistributionList(recipients);
            rapportRepository.save(savedRapport);
            
            // Notification WebSocket
            notifyNewRapport(savedRapport, request.getRecipientIds());
        }

        // Lancer la génération asynchrone des données
        generateRapportDataAsync(savedRapport.getId());

        return savedRapport;
    }

    @Async
    @Transactional
    public void generateRapportDataAsync(Long rapportId) {
        Rapport rapport = rapportRepository.findById(rapportId).orElse(null);
        if (rapport == null)
            return;

        try {
            AppUser generator = rapport.getGeneratedBy();
            Long managerId = null;
            if (generator != null) {
                if ("MANAGER".equals(generator.getRole().getName())) {
                    managerId = generator.getId();
                } else if ("ANALYST".equals(generator.getRole().getName()) && generator.getManager() != null) {
                    managerId = generator.getManager().getId();
                }
            }

            LocalDateTime start = rapport.getStartDate();
            LocalDateTime end = rapport.getEndDate();

            Map<String, Object> dataSummary = new HashMap<>();

            // Ajouter le nom du manager si l'auteur est un analyste
            if (generator != null && "ANALYST".equals(generator.getRole().getName()) && generator.getManager() != null) {
                AppUser manager = generator.getManager();
                String managerName = manager.getFirstName() + " " + manager.getLastName();
                dataSummary.put("managerName", managerName);
            }

            // 1. Statistiques Transactions & KPIs
            long totalTx = transactionRepository.countAllByCreatedAtBetweenAndManagerId(start, end, managerId);
            java.math.BigDecimal totalAmount = transactionRepository.sumAmountByCreatedAtBetweenAndManagerId(start, end,
                    managerId);
            if (totalAmount == null)
                totalAmount = java.math.BigDecimal.ZERO;

            Double avgScore = transactionRepository
                    .getAverageFraudScoreByCreatedAtBetweenAndManagerId(start, end, managerId).orElse(0.0);
            long blockedTx = transactionRepository.countBlockedByCreatedAtBetweenAndManagerId(start, end, managerId);

            long totalAlerts = (managerId != null)
                    ? fraudAlertRepository.countByCreatedAtBetweenAndManagerId(start, end, managerId) // This method
                                                                                                      // needs to be
                                                                                                      // checked/added
                    : fraudAlertRepository.countByCreatedAtBetween(start, end);

            long confirmedFrauds = (managerId != null)
                    ? fraudAlertRepository.countConfirmedByCreatedAtBetweenAndManagerId(start, end, managerId)
                    : fraudAlertRepository.countByStatusAndCreatedAtBetween("CONFIRMED", start, end) +
                            fraudAlertRepository.countByStatusAndCreatedAtBetween("CONFIRMÉE", start, end);

            long falsePositives = (managerId != null)
                    ? fraudAlertRepository.countFalsePositivesByCreatedAtBetweenAndManagerId(start, end, managerId)
                    : fraudAlertRepository.countByStatusAndCreatedAtBetween("FALSE_POSITIVE", start, end);

            double fraudRate = totalTx > 0 ? (double) confirmedFrauds / totalTx : 0.0;

            Map<String, Object> kpis = new HashMap<>();
            kpis.put("totalTransactions", totalTx);
            kpis.put("totalAmount", totalAmount);
            kpis.put("fraudDetected", confirmedFrauds);
            kpis.put("fraudRate", fraudRate);
            kpis.put("avgFraudScore", avgScore);
            kpis.put("totalAlerts", totalAlerts);
            kpis.put("confirmedFrauds", confirmedFrauds);
            kpis.put("falsePositives", falsePositives);
            kpis.put("blockedTransactions", blockedTx);

            dataSummary.put("kpis", kpis);

            // 2. Risk Distribution (Isolated)
            List<Object[]> riskDist = (managerId != null)
                    ? fraudAlertRepository.getRiskDistributionByManagerId(start, end, managerId)
                    : fraudAlertRepository.getRiskDistribution(start, end);

            Map<String, Long> riskMap = new HashMap<>();
            for (Object[] row : riskDist) {
                riskMap.put((String) row[0], (Long) row[1]);
            }
            dataSummary.put("riskDistribution", riskMap);

            // 3. Models Performance OR Dataset Tracking (Hierarchical filtering)
            if (generator != null && "MANAGER".equals(generator.getRole().getName())) {
                // Suivi des Datasets pour les Managers
                List<Dataset> datasets = datasetRepository.findByAssignedManagerIdAndCreatedAtBetween(generator.getId(), start, end);
                List<Map<String, Object>> datasetsData = new ArrayList<>();
                for (Dataset d : datasets) {
                    Map<String, Object> dMap = new HashMap<>();
                    dMap.put("datasetName", d.getDatasetName());
                    
                    // Priorité au statut "QUALIFIED" si le booléen est vrai
                    String displayStatus = "INACTIF";
                    if (Boolean.TRUE.equals(d.getQualified())) {
                        displayStatus = "QUALIFIED";
                    } else if (d.getStatus() != null) {
                        displayStatus = d.getStatus().toString();
                    }
                    
                    dMap.put("status", displayStatus);
                    dMap.put("rowCount", d.getRowCount());
                    dMap.put("fileSize", d.getFileSizeMb());
                    dMap.put("date", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
                    datasetsData.add(dMap);
                }
                dataSummary.put("datasets", datasetsData);
            } else {
                // Performance des Modèles (Comportement actuel pour les Analystes)
                List<ModelML> specificModels;
                if (generator != null) {
                    String role = generator.getRole().getName();
                    if ("ANALYST".equals(role)) {
                        specificModels = modelMLRepository.findByCreatedByIdOrderByCreatedAtDesc(generator.getId());
                    } else {
                        specificModels = modelMLRepository.findAllByOrderByCreatedAtDesc();
                    }
                } else {
                    specificModels = modelMLRepository.findByStatus("Deployed");
                }

                // Filtrer les modèles pour ne garder que ceux créés pendant la période d'analyse du rapport
                specificModels = specificModels.stream()
                        .filter(m -> {
                            LocalDateTime created = m.getCreatedAt();
                            if (created == null) return false;
                            return !created.isBefore(start) && !created.isAfter(end);
                        })
                        .collect(Collectors.toList());

                List<Map<String, Object>> modelsData = new ArrayList<>();
                for (ModelML m : specificModels) {
                    Map<String, Object> mData = new HashMap<>();
                    mData.put("modelId", m.getId());
                    mData.put("modelName", m.getModelName());
                    mData.put("version", m.getVersion());
                    mData.put("auc", m.getAuc());
                    mData.put("precision", m.getPrecision());
                    mData.put("recall", m.getRecall());
                    mData.put("f1Score", m.getF1Score());
                    modelsData.add(mData);
                }
                dataSummary.put("models", modelsData);
            }

            // 4. Alerts stats (Isolated)
            List<Object[]> alertStats = (managerId != null)
                    ? fraudAlertRepository.getTimeSeriesDataByManagerId(start, end, managerId)
                    : fraudAlertRepository.getTimeSeriesData(start, end);
            dataSummary.put("timeSeriesAlerts", alertStats);

            // Finalize Rapport
            rapport.setDataSummary(dataSummary);
            rapport.setTotalTransactions((int) totalTx);
            rapport.setFraudDetected((int) confirmedFrauds);
            rapport.setAvgFraudScore(avgScore);
            rapport.setStatus("COMPLETED");

            rapportRepository.save(rapport);
            log.info("Rapport {} généré avec succès pour le manager {}", rapport.getId(), managerId);

        } catch (Exception e) {
            log.error("Erreur lors de la génération du rapport {}: {}", rapportId, e.getMessage(), e);
            rapport.setStatus("FAILED");
            rapportRepository.save(rapport);
        }
    }

    public Page<Rapport> getMyGeneratedRapports(String userKeycloakId, Pageable pageable) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        return rapportRepository.findByGeneratedBy_IdOrderByCreatedAtDesc(user.getId(), pageable);
    }

    public Page<Rapport> getReceivedRapports(String userKeycloakId, Pageable pageable) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        return rapportRepository.findReceivedRapports(user.getId(), pageable);
    }

    @Transactional
    public void markAsRead(UUID rapportUuid, String userKeycloakId) {
        AppUser user = findUserByKeycloakId(userKeycloakId);

        Rapport rapport = rapportRepository.findByRapportUuid(rapportUuid)
                .orElseThrow(() -> new RuntimeException("Rapport non trouvé"));

        if (rapport.getDistributionList() != null) {
            List<Map<String, Object>> list = new ArrayList<>(rapport.getDistributionList());
            boolean updated = false;
            for (Map<String, Object> r : list) {
                if (String.valueOf(r.get("userId")).equals(String.valueOf(user.getId()))) {
                    r.put("isRead", true);
                    r.put("readAt", LocalDateTime.now().toString());
                    updated = true;
                }
            }
            if (updated) {
                rapport.setDistributionList(list);
                rapportRepository.save(rapport);
            }
        }
    }

    @Transactional
    public Rapport shareRapport(UUID rapportUuid, List<Long> newRecipientIds, String userKeycloakId) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        
        Rapport rapport = rapportRepository.findByRapportUuid(rapportUuid)
                .orElseThrow(() -> new RuntimeException("Rapport non trouvé"));

        // Vérifier que c'est bien l'auteur
        if (!rapport.getGeneratedBy().getId().equals(user.getId())) {
            throw new RuntimeException("Seul l'auteur peut partager ce rapport.");
        }

        if (newRecipientIds != null && !newRecipientIds.isEmpty()) {
            List<Map<String, Object>> currentList = rapport.getDistributionList();
            if (currentList == null) {
                currentList = new ArrayList<>();
            }

            // Récupérer les IDs existants pour éviter les doublons
            Set<Long> existingIds = currentList.stream()
                    .map(map -> Long.valueOf(map.get("userId").toString()))
                    .collect(Collectors.toSet());

            // Ne pas s'ajouter soi-même
            existingIds.add(user.getId());

            boolean updated = false;
            for (Long recipientId : newRecipientIds) {
                if (!existingIds.contains(recipientId)) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("userId", recipientId);
                    r.put("isRead", false);
                    r.put("readAt", null);
                    currentList.add(r);
                    updated = true;
                }
            }

            if (updated) {
                rapport.setDistributionList(currentList);
                Rapport saved = rapportRepository.save(rapport);
                
                // Notification uniquement aux nouveaux destinataires
                List<Long> newlyAddedIds = newRecipientIds.stream()
                        .filter(id -> !existingIds.contains(id))
                        .collect(Collectors.toList());
                notifyNewRapport(saved, newlyAddedIds);
                
                return saved;
            }
        }
        return rapport;
    }

    public Optional<Rapport> getRapportByUuid(UUID uuid) {
        return rapportRepository.findByRapportUuid(uuid);
    }

    public long getUnreadCount(String userKeycloakId) {
        AppUser user = findUserByKeycloakId(userKeycloakId);
        return rapportRepository.countUnreadRapports(user.getId());
    }
}