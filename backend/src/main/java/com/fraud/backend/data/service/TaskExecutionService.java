package com.fraud.backend.data.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TaskExecutionService {

    private final Map<String, TaskStatus> tasks = new ConcurrentHashMap<>();

    public String startTask(String taskType, Runnable taskLogic) {
        String taskId = UUID.randomUUID().toString();
        
        TaskStatus status = new TaskStatus();
        status.setTaskId(taskId);
        status.setType(taskType);
        status.setState(TaskState.RUNNING);
        status.setProgress(0);
        status.setStartTime(System.currentTimeMillis());
        tasks.put(taskId, status);

        // Launch async task
        executeTask(taskId, taskLogic, status);

        return taskId;
    }

    @Async
    protected void executeTask(String taskId, Runnable taskLogic, TaskStatus status) {
        try {
            taskLogic.run();
            status.setState(TaskState.COMPLETED);
            status.setProgress(100);
            status.setMessage("Tâche terminée avec succès");
            log.info("Task {} completed successfully", taskId);
        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage());
            status.setState(TaskState.FAILED);
            status.setErrorMessage(e.getMessage());
        }
    }

    public void updateProgress(String taskId, int progress, String message) {
        TaskStatus status = tasks.get(taskId);
        if (status != null && status.getState() == TaskState.RUNNING) {
            status.setProgress(progress);
            status.setMessage(message);
        }
    }

    public TaskStatus getTaskStatus(String taskId) {
        TaskStatus status = tasks.get(taskId);
        if (status == null) {
            throw new TaskNotFoundException("Tâche non trouvée: " + taskId);
        }
        return status;
    }

    public void cleanupCompletedTasks(long maxAgeMillis) {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(entry -> {
            TaskStatus status = entry.getValue();
            return (status.getState() == TaskState.COMPLETED || status.getState() == TaskState.FAILED)
                    && (now - status.getStartTime() > maxAgeMillis);
        });
    }

    public enum TaskState {
        RUNNING, COMPLETED, FAILED
    }

    @Data
    public static class TaskStatus {
        private String taskId;
        private String type;
        private TaskState state;
        private int progress;
        private String message;
        private String errorMessage;
        private long startTime;
    }

    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String message) {
            super(message);
        }
    }
}