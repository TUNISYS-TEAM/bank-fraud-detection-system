package com.fraud.backend.data.service.frauddetection.deployment;

import com.fraud.backend.data.entity.corebanking.ClientBehavior;
import com.fraud.backend.data.entity.corebanking.Transaction;
import com.fraud.backend.data.entity.frauddetection.ModelML;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InferenceService {

    Map<String, Object> predict(UUID modelUuid, Long accountId, UUID transactionUuid);

    List<Map<String, Object>> predictBatch(UUID modelUuid, List<Map<String, Object>> batchItems);

    double getModelThreshold(ModelML model);

    Map<String, Object> transactionToMap(Transaction tx);
    
    Map<String, Object> behaviorToMap(ClientBehavior behavior);
}