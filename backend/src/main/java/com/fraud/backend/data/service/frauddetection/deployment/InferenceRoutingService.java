package com.fraud.backend.data.service.frauddetection.deployment;

import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import com.fraud.backend.data.enums.InferenceEngine;
import com.fraud.backend.data.repository.frauddetection.ModelMLRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Routeur central qui oriente les requêtes d'inférence vers le bon service
 * en fonction du moteur défini pour le modèle.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class InferenceRoutingService implements InferenceService {

    private final PythonInferenceService onnxLocalService;
    private final HybridRestInferenceService hybridRestService;
    private final ModelMLRepository modelMLRepository;

    private InferenceService resolveService(UUID modelUuid) {
        ModelML model = modelMLRepository.findByModelUuid(modelUuid)
                .orElseThrow(() -> new IllegalArgumentException("Modèle non trouvé : " + modelUuid));

        if (model.getInferenceEngine() == InferenceEngine.HYBRID_SERVICE) {
            log.info("Routing inference for model {} to HYBRID_SERVICE", modelUuid);
            return hybridRestService;
        }

        log.info("Routing inference for model {} to ONNX_LOCAL service", modelUuid);
        return onnxLocalService;
    }

    @Override
    public Map<String, Object> predict(UUID modelUuid, Long accountId, UUID transactionUuid) {
        return resolveService(modelUuid).predict(modelUuid, accountId, transactionUuid);
    }

    @Override
    public List<Map<String, Object>> predictBatch(UUID modelUuid, List<Map<String, Object>> batchItems) {
        return resolveService(modelUuid).predictBatch(modelUuid, batchItems);
    }

    @Override
    public double getModelThreshold(ModelML model) {
        return onnxLocalService.getModelThreshold(model);
    }

    @Override
    public Map<String, Object> transactionToMap(Transaction tx) {
        return onnxLocalService.transactionToMap(tx);
    }

    @Override
    public Map<String, Object> behaviorToMap(ClientBehavior behavior) {
        return onnxLocalService.behaviorToMap(behavior);
    }
}