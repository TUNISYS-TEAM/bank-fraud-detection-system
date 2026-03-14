package com.fraud.backend.data.service.accessmanagement.archive;

import com.fraud.backend.data.entity.accessmanagement.AppUser;
import com.fraud.backend.data.entity.accessmanagement.ArchiveObject;
import com.fraud.backend.data.enums.archive.ArchiveStatus;
import com.fraud.backend.data.enums.archive.ArchiveType;
import com.fraud.backend.data.repository.accessmanagement.AppUserRepository;
import com.fraud.backend.data.repository.accessmanagement.ArchiveObjectRepository;
import com.fraud.backend.data.service.storage.StorageService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveObjectService {

    private final ArchiveObjectRepository archiveRepo;
    private final AppUserRepository appUserRepo;
    private final StorageService storageService;

    public Page<ArchiveObject> list(ArchiveType type, ArchiveStatus status, Pageable pageable) {
        if (type != null && status != null) {
            return archiveRepo.findByTypeAndStatusOrderByCreatedAtDesc(type, status, pageable);
        }
        if (type != null) {
            return archiveRepo.findByTypeOrderByCreatedAtDesc(type, pageable);
        }
        if (status != null) {
            return archiveRepo.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        return archiveRepo.findByStatusNotOrderByCreatedAtDesc(ArchiveStatus.DELETED, pageable);
    }

    public ArchiveObject get(Long id) {
        return archiveRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ArchiveObject not found: " + id));
    }

    @Transactional
    public ArchiveObject create(ArchiveObject archiveObject) {
        if (archiveObject.getEndAt().isBefore(archiveObject.getStartAt()) ||
                archiveObject.getEndAt().isEqual(archiveObject.getStartAt())) {
            throw new IllegalArgumentException("endAt must be > startAt");
        }
        return archiveRepo.save(archiveObject);
    }

    @Transactional
    public ArchiveObject updateStatus(Long id, ArchiveStatus status) {
        ArchiveObject ao = get(id);
        ao.setStatus(status);
        return archiveRepo.save(ao);
    }

    @Transactional
    public void deleteArchive(Long id) {
        ArchiveObject ao = archiveRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive non trouvée : " + id));

        // Supprimer le fichier depuis MinIO/S3
        try {
            if (ao.getObjectKey() != null) {
                storageService.delete(ao.getObjectKey());
                log.info("Deleted archive file from storage: {}", ao.getObjectKey());
            }
        } catch (Exception e) {
            log.warn("Failed to delete archive file from storage: {}. Proceeding with status update.",
                    ao.getObjectKey(),
                    e);
        }

        // Au lieu de supprimer la ligne, on change le statut
        ao.setStatus(ArchiveStatus.DELETED);
        archiveRepo.save(ao);
        log.info("Updated archive_object status to DELETED for id={}", id);
    }

    @Transactional
    public void deleteAllArchives() {
        log.info("Starting total purge of all archives from storage (keeping DB rows)...");

        // 1) Récupérer toutes les archives non déjà supprimées
        java.util.List<ArchiveObject> all = archiveRepo.findAll();

        // 2) Supprimer physiquement de MinIO et mettre à jour le statut
        int count = 0;
        for (ArchiveObject ao : all) {
            if (ao.getStatus() == ArchiveStatus.DELETED)
                continue;

            try {
                if (ao.getObjectKey() != null) {
                    storageService.delete(ao.getObjectKey());
                }
                ao.setStatus(ArchiveStatus.DELETED);
                archiveRepo.save(ao);
                count++;
            } catch (Exception e) {
                log.warn("Failed to delete file {} during total purge", ao.getObjectKey(), e);
            }
        }

        log.info("Total purge completed. {} archives marked as DELETED in DB and removed from storage.", count);
    }

    @Transactional(readOnly = true)
    public AppUser getUserOrNull(Long userId) {
        if (userId == null)
            return null;
        return appUserRepo.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("AppUser not found: " + userId));
    }
}