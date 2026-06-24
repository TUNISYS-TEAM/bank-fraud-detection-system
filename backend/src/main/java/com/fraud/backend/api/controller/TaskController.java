package com.fraud.backend.api.controller;

import com.fraud.backend.data.service.TaskExecutionService;
import com.fraud.backend.data.service.TaskExecutionService.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskExecutionService taskExecutionService;

    @GetMapping("/{taskId}/status")
    public ResponseEntity<TaskStatus> getTaskStatus(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(taskExecutionService.getTaskStatus(taskId));
        } catch (TaskExecutionService.TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}