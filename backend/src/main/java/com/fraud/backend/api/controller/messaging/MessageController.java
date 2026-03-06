package com.fraud.backend.api.controller.messaging;

import com.fraud.backend.api.dto.messaging.request.SendMessageRequest;
import com.fraud.backend.data.entity.messaging.Message;
import com.fraud.backend.data.service.messaging.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Message> sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SendMessageRequest request) {
        
        Long senderId = jwt.getClaim("user_id");
        Message message = messageService.sendMessage(senderId, request);
        
        return ResponseEntity.ok(message);
    }

    @GetMapping("/inbox")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<Message>> getInbox(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Long userId = jwt.getClaim("user_id");
        Page<Message> messages = messageService.getInbox(userId, page, size);
        
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/sent")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<Message>> getSent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Long userId = jwt.getClaim("user_id");
        Page<Message> messages = messageService.getSent(userId, page, size);
        
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/conversation/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<Message>> getConversation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Long currentUserId = jwt.getClaim("user_id");
        Page<Message> messages = messageService.getConversation(currentUserId, userId, page, size);
        
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/unread/count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("user_id");
        long count = messageService.getUnreadCount(userId);
        
        Map<String, Long> response = new HashMap<>();
        response.put("count", count);
        
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{messageUuid}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID messageUuid) {
        
        Long userId = jwt.getClaim("user_id");
        Message message = messageService.getMessage(messageUuid);
        
        if (message.getReceiver().getId().equals(userId)) {
            messageService.markAsRead(message.getId(), userId);
        }
        
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> markAllAsRead(@AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("user_id");
        messageService.markAllAsRead(userId);
        
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{messageUuid}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Message> getMessage(@PathVariable UUID messageUuid) {
        Message message = messageService.getMessage(messageUuid);
        return ResponseEntity.ok(message);
    }
}