package com.fraud.backend.data.service.accessmanagement.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraud.backend.data.entity.accessmanagement.ArchiveObject;
import com.fraud.backend.data.enums.archive.ArchiveFormat;
import com.fraud.backend.data.enums.archive.ArchiveStatus;
import com.fraud.backend.data.enums.archive.ArchiveType;
import com.fraud.backend.data.repository.accessmanagement.ArchiveObjectRepository;
import com.fraud.backend.data.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveJobService {

    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final ArchiveObjectRepository archiveObjectRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Lancement de l'archivage des logs
    @Transactional
    public ArchiveObject runAuditLogArchive() {
        return runGenericArchive(
                ArchiveType.AUDIT_LOG,
                "audit_log",
                "action_timestamp",
                "id, session_id, action, action_type, action_timestamp, level",
                "audit",
                "retention_days");
    }

    // Lancement de l'archivage des sessions
    @Transactional
    public ArchiveObject runUserSessionArchive() {
        return runGenericArchive(
                ArchiveType.USER_SESSION,
                "user_session",
                "login_time",
                "id, user_id, session_id, keycloak_session_id, ip_address, user_agent, login_time, last_activity, logout_time, is_active, created_at",
                "session",
                "retention_days");
    }

    private ArchiveObject runGenericArchive(
            ArchiveType type,
            String tableName,
            String timestampColumn,
            String selectColumns,
            String filePrefix,
            String retentionKey) {

        boolean enabled = getBooleanSetting("archive_enabled", true);
        double retentionDays = getDoubleSetting(retentionKey, 90.0);

        if (!enabled) {
            log.warn("Archiving is disabled for type: {}", type);
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes((long) (retentionDays * 1440.0));
        log.info("Archiving {}: retentionDays={}, cutoff={}", type, retentionDays, cutoff);

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    String.format("SELECT %s FROM %s WHERE %s < ? ORDER BY %s ASC",
                            selectColumns, tableName, timestampColumn, timestampColumn),
                    Timestamp.valueOf(cutoff));
        } catch (DataAccessException e) {
            log.error("Failed to read {} for archiving", tableName, e);
            throw new IllegalStateException("Failed to read database for archiving " + type, e);
        }

        if (rows.isEmpty()) {
            log.info("No {} rows to archive", tableName);
            return null;
        }

        LocalDateTime startAt = toLocalDateTime(rows.get(0).get(timestampColumn));
        LocalDateTime endAt = toLocalDateTime(rows.get(rows.size() - 1).get(timestampColumn));
        String objectKey = buildObjectKey(type, startAt, endAt, filePrefix);

        ArchiveObject ao = ArchiveObject.builder()
                .type(type)
                .startAt(startAt)
                .endAt(endAt)
                .bucket("fraud-detection-archives")
                .objectKey(objectKey)
                .format(ArchiveFormat.JSONL_GZ)
                .status(ArchiveStatus.PENDING)
                .build();

        ao = archiveObjectRepository.save(ao);

        try {
            byte[] gz = toJsonlGz(rows);

            storageService.upload(
                    objectKey,
                    new ByteArrayInputStream(gz),
                    gz.length,
                    "application/gzip");

            jdbcTemplate.update(
                    String.format("DELETE FROM %s WHERE %s < ?", tableName, timestampColumn),
                    Timestamp.valueOf(cutoff));

            ao.setStatus(ArchiveStatus.CREATED);
            ao = archiveObjectRepository.save(ao);

            log.info("{} archived: id={}, key={}, rows={}", type, ao.getId(), objectKey, rows.size());
            return ao;

        } catch (Exception ex) {
            ao.setStatus(ArchiveStatus.FAILED);
            archiveObjectRepository.save(ao);
            log.error("{} archiving FAILED: key={}", type, objectKey, ex);
            return ao;
        }
    }

    // Restoration des archives
    public void restoreFromArchive(Long archiveId) {
        ArchiveObject ao = archiveObjectRepository.findById(archiveId)
                .orElseThrow(() -> new IllegalArgumentException("Archive not found: " + archiveId));

        if (ao.getStatus() != ArchiveStatus.CREATED && ao.getStatus() != ArchiveStatus.RESTORED) {
            throw new IllegalStateException("Archive is not in a restorable status (CREATED or RESTORED)");
        }

        String tableName = ao.getType() == ArchiveType.AUDIT_LOG ? "audit_log" : "user_session";
        String columns = ao.getType() == ArchiveType.AUDIT_LOG
                ? "id, session_id, action, action_type, action_timestamp, level"
                : "id, user_id, session_id, keycloak_session_id, ip_address, user_agent, login_time, last_activity, logout_time, is_active, created_at";

        int columnCount = columns.split(",").length;
        String placeholders = String.join(", ", Collections.nCopies(columnCount, "?"));

        log.info("Restoring {} from archive {}", tableName, archiveId);

        List<Object[]> batchArgs = new ArrayList<>();
        int batchSize = 100;
        int count = 0;

        try (InputStream is = storageService.download(ao.getObjectKey());
                GZIPInputStream gzis = new GZIPInputStream(is);
                InputStreamReader isr = new InputStreamReader(gzis, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(isr)) {

            String line;
            String sql = String.format("INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (id) DO NOTHING",
                    tableName, columns, placeholders);

            while ((line = reader.readLine()) != null) {
                try {
                    Map<String, Object> row = objectMapper.readValue(line, Map.class);
                    batchArgs.add(buildInsertArgs(ao.getType(), row));
                    count++;

                    if (batchArgs.size() >= batchSize) {
                        jdbcTemplate.batchUpdate(sql, batchArgs);
                        batchArgs.clear();
                    }
                } catch (Exception rowEx) {
                    log.error("Error preparing row {} in archive {}: {}", count, archiveId, rowEx.getMessage());
                }
            }

            // Flush remaining rows
            if (!batchArgs.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batchArgs);
            }

            // Update status using JPA repository for proper Enum handling
            ao.setStatus(ArchiveStatus.RESTORED);
            archiveObjectRepository.save(ao);

            log.info("Restoration completed for archive {}: {} rows processed.", archiveId, count);

            // Suppression physique du fichier MinIO puisque restauré en base
            try {
                if (ao.getObjectKey() != null) {
                    storageService.delete(ao.getObjectKey());
                    log.info("Deleted archive file from storage after restoration: {}", ao.getObjectKey());
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to delete archive file {} from storage after restoration. Manual cleanup might be needed.",
                        ao.getObjectKey(), e);
            }

        } catch (Throwable e) {
            log.error("CATASTROPHIC failure restoring archive {}: {}", archiveId, e.getMessage());
            e.printStackTrace();
            // We set status to FAILED via JPA
            try {
                ao.setStatus(ArchiveStatus.FAILED);
                archiveObjectRepository.save(ao);
            } catch (Exception ignored) {
                log.error("Failed to set FAILED status for archive {}", archiveId);
            }

            if (e instanceof RuntimeException re)
                throw re;
            throw new RuntimeException("Restoration failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void saveArchiveStatus(ArchiveObject ao, ArchiveStatus status) {
        ao.setStatus(status);
        archiveObjectRepository.save(ao);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getArchivePreview(Long archiveId) {
        ArchiveObject ao = archiveObjectRepository.findById(archiveId)
                .orElseThrow(() -> new IllegalArgumentException("Archive not found: " + archiveId));

        int rowCount = 0;
        try (InputStream is = storageService.download(ao.getObjectKey());
                GZIPInputStream gzis = new GZIPInputStream(is);
                InputStreamReader isr = new InputStreamReader(gzis, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(isr)) {

            while (reader.readLine() != null) {
                rowCount++;
            }
        } catch (IOException e) {
            log.error("Failed to preview archive {}", archiveId, e);
            throw new RuntimeException("Failed to read archive for preview", e);
        }

        Map<String, Object> preview = new HashMap<>();
        preview.put("rowCount", rowCount);
        preview.put("type", ao.getType());
        preview.put("startAt", ao.getStartAt());
        preview.put("endAt", ao.getEndAt());
        return preview;
    }

    private Object[] buildInsertArgs(ArchiveType type, Map<String, Object> row) {
        if (type == ArchiveType.AUDIT_LOG) {
            return new Object[] {
                    toLong(row.get("id")),
                    row.get("session_id"),
                    row.get("action"),
                    row.get("action_type"),
                    toTimestamp(row.get("action_timestamp")),
                    row.get("level")
            };
        } else {
            return new Object[] {
                    toLong(row.get("id")),
                    toLong(row.get("user_id")),
                    row.get("session_id"),
                    row.get("keycloak_session_id"),
                    row.get("ip_address"),
                    row.get("user_agent"),
                    toTimestamp(row.get("login_time")),
                    toTimestamp(row.get("last_activity")),
                    toTimestamp(row.get("logout_time")),
                    toBoolean(row.get("is_active")),
                    toTimestamp(row.get("created_at"))
            };
        }
    }

    private Long toLong(Object v) {
        if (v == null)
            return null;
        if (v instanceof Number n)
            return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean toBoolean(Object v) {
        if (v == null)
            return null;
        if (v instanceof Boolean b)
            return b;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private Timestamp toTimestamp(Object v) {
        if (v == null || "null".equals(String.valueOf(v)) || String.valueOf(v).trim().isEmpty())
            return null;

        String s = String.valueOf(v).trim();

        // Handle ISO-8601 with T
        s = s.replace("T", " ");
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1);
        }

        try {
            return Timestamp.valueOf(s);
        } catch (Exception e) {
            log.warn("Failed to parse timestamp: {}. Trying alternative format.", s);
            try {
                if (s.length() > 19) {
                    return Timestamp.valueOf(s.substring(0, 19));
                }
                return Timestamp.valueOf(s);
            } catch (Exception e2) {
                log.error("CATASTROPHIC timestamp parse error for: {}", s);
                return null;
            }
        }
    }

    // Helpers settings
    public boolean getBooleanSetting(String key, boolean defaultValue) {
        String v = getSettingValue(key);
        return v == null ? defaultValue : "true".equalsIgnoreCase(v.trim());
    }

    public double getDoubleSetting(String key, double defaultValue) {
        String v = getSettingValue(key);
        try {
            return v == null ? defaultValue : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getSettingValue(String key) {
        try {
            List<String> res = jdbcTemplate.query(
                    "SELECT value FROM system_settings WHERE key = ?",
                    (rs, rowNum) -> rs.getString("value"),
                    key);
            return res.isEmpty() ? null : res.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    // Helpers export / naming
    private String buildObjectKey(ArchiveType type, LocalDateTime startAt, LocalDateTime endAt, String prefix) {
        LocalDate d = startAt.toLocalDate();
        String dayPath = d.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String s = startAt.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String e = endAt.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String folder = type == ArchiveType.AUDIT_LOG ? "audit_logs" : "user_sessions";
        return String.format("archive/%s/%s/%s_%s_to_%s.jsonl.gz", folder, dayPath, prefix, s, e);
    }

    private byte[] toJsonlGz(List<Map<String, Object>> rows) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos);
                OutputStreamWriter osw = new OutputStreamWriter(gzip, StandardCharsets.UTF_8);
                BufferedWriter bw = new BufferedWriter(osw)) {
            for (Map<String, Object> r : rows) {
                bw.write(objectMapper.writeValueAsString(normalizeRow(r)));
                bw.newLine();
            }
        }
        return bos.toByteArray();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        for (Map.Entry<String, Object> entry : r.entrySet()) {
            if (entry.getValue() instanceof Timestamp t) {
                out.put(entry.getKey(), String.valueOf(t));
            } else if (entry.getValue() instanceof LocalDateTime ldt) {
                out.put(entry.getKey(), String.valueOf(ldt));
            }
        }
        return out;
    }

    private LocalDateTime toLocalDateTime(Object v) {
        if (v == null)
            return LocalDateTime.now();
        if (v instanceof Timestamp t)
            return t.toLocalDateTime();
        if (v instanceof LocalDateTime ldt)
            return ldt;
        try {
            return LocalDateTime.parse(String.valueOf(v).replace(" ", "T"));
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}