package com.fraud.backend.api.controller.accessmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fraud.backend.api.dto.accessmanagement.auditlog.AuditLogMapper;
import com.fraud.backend.api.dto.accessmanagement.auditlog.AuditLogResponse;
import com.fraud.backend.data.entity.accessmanagement.AuditLog;
import com.fraud.backend.data.service.accessmanagement.AuditLogService;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.List;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AuditLogMapper auditLogMapper;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLogResponse>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String level) {

        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(
                auditLogService.getLogs(actionType, level, pageable)
                        .map(auditLogMapper::toResponse));
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuditLogResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(auditLogMapper.toResponse(auditLogService.getById(id)));
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportLogs(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String level) {

        List<AuditLog> logs = auditLogService.getAllLogsForExport(actionType, level);

        return switch (format.toLowerCase()) {
            case "csv" -> exportCsv(logs);
            case "pdf" -> exportPdf(logs);
            case "json" -> exportJson(logs);
            default -> ResponseEntity.badRequest().build();
        };
    }

    // ─── CSV ────────────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> exportCsv(List<AuditLog> logs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out);

        writer.println("ID,Session ID,Action,Action Type,Timestamp,Level");
        for (AuditLog log : logs) {
            writer.printf("%d,%s,%s,%s,%s,%s%n",
                    log.getId(),
                    log.getSession() != null ? log.getSession().getId() : "",
                    escapeCsv(log.getAction()),
                    escapeCsv(log.getActionType()),
                    log.getActionTimestamp(),
                    log.getLevel());
        }
        writer.flush();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit_logs.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(out.toByteArray());
    }

    // ─── PDF ────────────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> exportPdf(List<AuditLog> logs) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4.rotate());
            PdfWriter.getInstance(document, out);
            document.open();

            // Title
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, BaseColor.DARK_GRAY);
            Paragraph title = new Paragraph("Audit Logs", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            // Table
            PdfPTable table = new PdfPTable(6);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{1f, 1.5f, 3f, 2f, 2.5f, 1.5f});

            // Header
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, BaseColor.WHITE);
            BaseColor headerColor = new BaseColor(59, 130, 246);
            String[] headers = {"ID", "Session ID", "Action", "Action Type", "Timestamp", "Level"};
            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                cell.setBackgroundColor(headerColor);
                cell.setPadding(6);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(cell);
            }

            // Rows
            Font rowFont = FontFactory.getFont(FontFactory.HELVETICA, 8, BaseColor.DARK_GRAY);
            boolean alternate = false;
            BaseColor altColor = new BaseColor(243, 244, 246);

            for (AuditLog log : logs) {
                BaseColor rowColor = alternate ? altColor : BaseColor.WHITE;
                String[] values = {
                        String.valueOf(log.getId()),
                        log.getSession() != null ? String.valueOf(log.getSession().getId()) : "-",
                        log.getAction() != null ? log.getAction() : "-",
                        log.getActionType() != null ? log.getActionType() : "-",
                        log.getActionTimestamp() != null ? log.getActionTimestamp().toString() : "-",
                        log.getLevel() != null ? log.getLevel() : "-"
                };
                for (String val : values) {
                    PdfPCell cell = new PdfPCell(new Phrase(val, rowFont));
                    cell.setBackgroundColor(rowColor);
                    cell.setPadding(5);
                    table.addCell(cell);
                }
                alternate = !alternate;
            }

            document.add(table);
            document.close();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit_logs.pdf")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(out.toByteArray());

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─── JSON ───────────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> exportJson(List<AuditLog> logs) {
        try {
            List<AuditLogResponse> responses = logs.stream()
                    .map(auditLogMapper::toResponse)
                    .toList();

            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(responses);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit_logs.json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ─── Utils ──────────────────────────────────────────────────────────────────

    private String escapeCsv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @GetMapping("/count/today")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Long> countToday() {
        return ResponseEntity.ok(auditLogService.countToday());
    }
}