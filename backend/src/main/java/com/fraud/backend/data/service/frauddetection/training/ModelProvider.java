package com.fraud.backend.data.service.frauddetection.training;

import com.fraud.backend.data.entity.frauddetection.ModelML;

import java.util.Optional;


public interface ModelProvider {
    
    /**
     * Get the currently deployed model entity for the current user or a specific manager
     * @param managerId Optional manager ID. If null, it uses the current user's effective manager.
     * @return Optional containing the deployed model, or empty if none deployed
     */
    Optional<ModelML> getDeployedModelEntity(Long managerId);

    default Optional<ModelML> getDeployedModelEntity() {
        return getDeployedModelEntity(null);
    }
}