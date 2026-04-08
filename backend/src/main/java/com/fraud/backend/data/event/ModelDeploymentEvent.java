package com.fraud.backend.data.event;

import lombok.Getter;
import java.util.UUID;

@Getter
public class ModelDeploymentEvent {
    private final UUID modelUuid;
    private final Action action;
    private final Long managerId;

    public enum Action {
        DEPLOYED,
        UNDEPLOYED
    }

    public ModelDeploymentEvent(UUID modelUuid, Action action, Long managerId) {
        this.modelUuid = modelUuid;
        this.action = action;
        this.managerId = managerId;
    }
}