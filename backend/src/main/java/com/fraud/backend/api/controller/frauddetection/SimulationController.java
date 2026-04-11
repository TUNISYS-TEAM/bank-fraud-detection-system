package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.model.request.SimulationRequest;
import com.fraud.backend.api.dto.frauddetection.model.response.ModelSummaryResponse;
import com.fraud.backend.api.dto.frauddetection.model.response.SimulationResponse;
import com.fraud.backend.data.service.frauddetection.simulation.SimulationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    @PostMapping("/run")
    public ResponseEntity<SimulationResponse> runSimulation(@Valid @RequestBody SimulationRequest request) {
        log.info("Simulation requested for model: {}", request.getModelUuid());
        SimulationResponse response = simulationService.runSimulation(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/models")
    public ResponseEntity<List<ModelSummaryResponse>> getAvailableModels() {
        log.info("Fetching available models for simulation");
        return ResponseEntity.ok(simulationService.getAvailableModelsForSimulation());
    }
}