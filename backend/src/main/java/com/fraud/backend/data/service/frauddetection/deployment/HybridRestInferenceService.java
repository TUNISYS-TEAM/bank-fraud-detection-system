package com.fraud.backend.data.service.frauddetection.deployment;

import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.ModelML;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRestInferenceService implements InferenceService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final PythonInferenceService pythonInferenceService;
    private final com.fraud.backend.data.repository.frauddetection.ModelMLRepository modelMLRepository;
    private final com.fraud.backend.data.repository.corebanking.TransactionRepository transactionRepository;
    private final com.fraud.backend.data.repository.corebanking.ClientBehaviorRepository clientBehaviorRepository;

    @Value("${fraud.hybrid.service-url:http://ml-hybrid-service:8000}")
    private String hybridServiceUrl;

    @Override
    public Map<String, Object> predict(UUID modelUuid, Long accountId, UUID transactionUuid) {
        log.info("Calling Hybrid REST inference for model {}", modelUuid);
        
        Transaction transaction = transactionRepository.findByTransactionUuid(transactionUuid)
                .orElseThrow(() -> new IllegalArgumentException("Transaction non trouvée: " + transactionUuid));

        ClientBehavior existingBehavior = clientBehaviorRepository
                .findByAccountId(accountId)
                .orElse(null);

        Map<String, Object> request = new HashMap<>();
        request.put("model_uuid", modelUuid.toString());
        request.put("transaction", transactionToMap(transaction));
        request.put("has_existing_behavior", existingBehavior != null);
        
        // On récupère les chemins d'artefacts (modèle + pipeline)
        Map<String, String> paths = extractArtifactPaths(modelUuid);
        request.put("storage_path", paths.get("modelPath"));
        request.put("pipeline_path", paths.get("pipelinePath"));

        if (existingBehavior != null) {
            request.put("existing_behavior", behaviorToMap(existingBehavior));
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(hybridServiceUrl + "/predict", request, Map.class);
            
            if (result == null) {
                throw new RuntimeException("Hybrid service returned null response for single prediction");
            }
            return result;
        } catch (Exception e) {
            log.error("Hybrid REST prediction failed for transaction {} (Model: {}). Error: {}", 
                    transactionUuid, modelUuid, e.getMessage(), e);
            throw new RuntimeException("Hybrid inference failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> predictBatch(UUID modelUuid, List<Map<String, Object>> batchItems) {
        String url = hybridServiceUrl + "/predict-batch";
        log.info("Calling Hybrid REST batch inference for model {} ({} items)", modelUuid, batchItems.size());
        
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model_uuid", modelUuid.toString());
            request.put("batch", batchItems);

            Map<String, String> paths = extractArtifactPaths(modelUuid);
            request.put("storage_path", paths.get("modelPath"));
            request.put("pipeline_path", paths.get("pipelinePath"));
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            
            if (response == null) {
                throw new RuntimeException("Hybrid service returned null response for batch prediction");
            }

            Object resultsObj = response.get("results");
            if (resultsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;
                return results;
            }
            
            log.warn("Hybrid service returned results in an unexpected format: {}", resultsObj);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Hybrid REST batch prediction failed for model {}: {}", modelUuid, e.getMessage(), e);
            throw new RuntimeException("Hybrid batch inference failed: " + e.getMessage(), e);
        }
    }

    @Override
    public double getModelThreshold(ModelML model) {
        return pythonInferenceService.getModelThreshold(model);
    }

    @Override
    public Map<String, Object> transactionToMap(Transaction tx) {
        return pythonInferenceService.transactionToMap(tx);
    }

    @Override
    public Map<String, Object> behaviorToMap(ClientBehavior behavior) {
        return pythonInferenceService.behaviorToMap(behavior);
    }

    private Map<String, String> extractArtifactPaths(UUID modelUuid) {
        return modelMLRepository.findByModelUuid(modelUuid)
                .map(model -> {
                    Map<String, String> paths = new HashMap<>();
                    Map<String, Object> hp = model.getHyperparameters();
                    if (hp != null && hp.containsKey("_trainingResults")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> results = (Map<String, Object>) hp.get("_trainingResults");
                        paths.put("modelPath", (String) results.get("modelPath"));
                        paths.put("pipelinePath", (String) results.get("pipelinePath"));
                    }
                    return paths;
                })
                .orElse(Collections.emptyMap());
    }
}