package com.fraud.backend.api.controller.messaging;

import com.fraud.backend.api.dto.messaging.MessageDTO;
import com.fraud.backend.api.dto.messaging.MessageMapper;
import com.fraud.backend.api.dto.messaging.request.SendMessageRequest;
import com.fraud.backend.api.dto.messaging.request.ModelValidationActionDTO;
import com.fraud.backend.data.entity.messaging.Message;
import com.fraud.backend.data.service.messaging.MessageService;
import com.fraud.backend.data.service.frauddetection.ModelTrainingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final MessageMapper messageMapper;
    private final ModelTrainingService modelTrainingService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MessageDTO> sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SendMessageRequest request) {

        String senderKeycloakId = jwt.getSubject();
        Message message = messageService.sendMessage(senderKeycloakId, request);
        return ResponseEntity.ok(messageMapper.toDto(message));
    }

    @GetMapping("/inbox")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MessageDTO>> getInbox(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String userKeycloakId = jwt.getSubject();
        Page<MessageDTO> messages = messageService.getInbox(userKeycloakId, page, size)
                .map(messageMapper::toDto);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/validation")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MessageDTO>> getValidationMessages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String userKeycloakId = jwt.getSubject();
        Page<MessageDTO> messages = messageService.getValidationMessages(userKeycloakId, page, size)
                .map(messageMapper::toDto);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/sent")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MessageDTO>> getSent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userKeycloakId = jwt.getSubject();
        Page<MessageDTO> messages = messageService.getSent(userKeycloakId, page, size)
                .map(messageMapper::toDto);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/archived")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MessageDTO>> getArchived(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userKeycloakId = jwt.getSubject();
        Page<MessageDTO> messages = messageService.getArchived(userKeycloakId, page, size)
                .map(messageMapper::toDto);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/conversation/{userKeycloakId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MessageDTO>> getConversation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userKeycloakId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String currentUserKeycloakId = jwt.getSubject();
        Page<MessageDTO> messages = messageService.getConversation(currentUserKeycloakId, userKeycloakId, page, size)
                .map(messageMapper::toDto);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/unread/count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@AuthenticationPrincipal Jwt jwt) {
        String userKeycloakId = jwt.getSubject();
        long count = messageService.getUnreadCount(userKeycloakId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/{messageUuid}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID messageUuid) {

        String userKeycloakId = jwt.getSubject();
        int result = messageService.markAsReadByUuid(messageUuid, userKeycloakId);

        if (result == 1) {
            return ResponseEntity.ok().build();
        } else if (result == -1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PatchMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> markAllAsRead(@AuthenticationPrincipal Jwt jwt) {
        String userKeycloakId = jwt.getSubject();
        messageService.markAllAsRead(userKeycloakId);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/archive")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> archiveMessages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, java.util.List<UUID>> request) {
        String userKeycloakId = jwt.getSubject();
        messageService.archiveMessages(userKeycloakId, request.get("messageUuids"));
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/restore")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> restoreMessages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, java.util.List<UUID>> request) {
        String userKeycloakId = jwt.getSubject();
        messageService.restoreMessages(userKeycloakId, request.get("messageUuids"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{messageUuid}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MessageDTO> getMessage(@PathVariable UUID messageUuid) {
        Message message = messageService.getMessage(messageUuid);
        return ResponseEntity.ok(messageMapper.toDto(message));
    }

    @PostMapping("/validate-model")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Void> validateModel(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody ModelValidationActionDTO request) {
        
        String managerKeycloakId = jwt.getSubject();
        modelTrainingService.validateModel(request, managerKeycloakId);
        return ResponseEntity.ok().build();
    }
}