package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.security.CallerContext;
import com.operativus.agentmanager.control.service.AuditLogService;
import com.operativus.agentmanager.core.entity.AgentAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain Responsibility: REST API boundary for the admin audit log dashboard.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * @summary Paginated, filtered list of agent_audits rows. Date-range filters
     *     ({@code createdAtFrom}/{@code createdAtTo}) accept ISO-8601 timestamps
     *     (e.g. {@code 2026-01-15T00:00:00}). {@code from} is inclusive;
     *     {@code to} is exclusive — pin compliance-query semantics where
     *     {@code [2026-01-01, 2026-02-01)} captures all of January.
     */
    @GetMapping
    public ResponseEntity<Page<AgentAuditEntity>> listAuditLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtTo,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        String orgId = CallerContext.resolveCallerOrgId();
        Objects.requireNonNull(orgId, "No authenticated org — cannot scope audit query");
        return ResponseEntity.ok(auditLogService.listAuditLogs(orgId, username, action, agentId,
                createdAtFrom, createdAtTo, pageable));
    }

    /**
     * @summary Streams filtered agent_audits rows as a CSV download (T013). Capped at
     *     {@link AuditLogService#MAX_EXPORT_ROWS} server-side to bound response size.
     * @logic Same filter semantics as {@link #listAuditLogs}. Returns
     *     {@code Content-Type: text/csv} + a {@code Content-Disposition: attachment}
     *     header so browsers offer a save-as dialog rather than rendering inline.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> exportAuditLogsCsv(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtTo) {
        String orgId = CallerContext.resolveCallerOrgId();
        Objects.requireNonNull(orgId, "No authenticated org — cannot scope audit export");
        String csv = auditLogService.exportAsCsv(orgId, username, action, agentId,
                createdAtFrom, createdAtTo);
        String filename = "audit-logs-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
