package com.fraud.backend.data.service.frauddetection;

import com.fraud.backend.data.entity.frauddetection.ModelML;

import java.util.Optional;


public interface ModelProvider {
    
    /**
     * Get the currently deployed model entity
     * @return Optional containing the deployed model, or empty if none deployed
     */
    Optional<ModelML> getDeployedModelEntity();
}