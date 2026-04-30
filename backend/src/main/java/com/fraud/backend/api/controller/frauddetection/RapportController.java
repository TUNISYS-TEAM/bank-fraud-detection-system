package com.fraud.backend.api.controller.frauddetection;

import com.fraud.backend.api.dto.frauddetection.rapport.RapportMapper;
import com.fraud.backend.api.dto.frauddetection.rapport.request.GenerateRapportRequest;
import com.fraud.backend.api.dto.frauddetection.rapport.response.RapportResponse;
import com.fraud.backend.data.entity.accessmanagement.KeycloakMapping;
import com.fraud.backend.data.entity.frauddetection.Rapport;
import com.fraud.backend.data.service.frauddetection.reporting.RapportService;
import com.fraud.backend.data.repository.accessmanagement.KeycloakMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rapports")
@RequiredArgsConstructor
public class RapportController {

    private final RapportService rapportService;
    private final RapportMapper rapportMapper;
    private final KeycloakMappingRepository keycloakMappingRepository;

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER')")
    public ResponseEntity<RapportResponse> generateRapport(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody GenerateRapportRequest request) {
        
        Rapport rapport = rapportService.initiateRapport(request, jwt.getSubject());
        return ResponseEntity.ok(rapportMapper.toResponse(rapport));
    }

    @GetMapping("/generated")
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER')")
    public ResponseEntity<Page<RapportResponse>> getMyGeneratedRapports(
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable) {
        
        Page<RapportResponse> response = rapportService.getMyGeneratedRapports(jwt.getSubject(), pageable)
                .map(rapportMapper::toResponse);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/received")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<RapportResponse>> getReceivedRapports(
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable) {
        
        var user = keycloakMappingRepository.findByKeycloakId(jwt.getSubject())
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
                
        Page<RapportResponse> response = rapportService.getReceivedRapports(jwt.getSubject(), pageable)
                .map(r -> rapportMapper.toResponse(r, user.getId()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{uuid}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RapportResponse> getRapport(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID uuid) {
        
        var user = keycloakMappingRepository.findByKeycloakId(jwt.getSubject())
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
                
        return rapportService.getRapportByUuid(uuid)
                .map(r -> rapportMapper.toResponse(r, user.getId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{uuid}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID uuid) {
        
        rapportService.markAsRead(uuid, jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal Jwt jwt) {
        
        return ResponseEntity.ok(Map.of("count", rapportService.getUnreadCount(jwt.getSubject())));
    }

    @PatchMapping("/{uuid}/share")
    @PreAuthorize("hasAnyRole('ANALYST', 'MANAGER')")
    public ResponseEntity<RapportResponse> shareRapport(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID uuid,
            @RequestBody Map<String, java.util.List<Long>> body) {
        
        java.util.List<Long> newRecipientIds = body.get("recipientIds");
        Rapport updatedRapport = rapportService.shareRapport(uuid, newRecipientIds, jwt.getSubject());
        
        var user = keycloakMappingRepository.findByKeycloakId(jwt.getSubject())
                .map(KeycloakMapping::getUser)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
                
        return ResponseEntity.ok(rapportMapper.toResponse(updatedRapport, user.getId()));
    }
}