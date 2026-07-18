package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.security.CallerContext;
import ai.operativus.agentmanager.control.service.SystemAuditService;
import ai.operativus.agentmanager.core.entity.SystemAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Domain Responsibility: Admin read surface over {@code system_audits} — the generalized audit
 *   log for non-agent HTTP mutations and authentication events. Scoping rule: the caller's
 *   {@code orgId} (resolved from their authenticated principal) is applied as a hard filter
 *   so admins of org A never see rows from org B. Matrix §24 case 6.
 * State: Stateless
 */
@RestController
@RequestMapping("/api/admin/system-audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class SystemAuditLogController {

    private final SystemAuditService systemAuditService;

    public SystemAuditLogController(SystemAuditService systemAuditService) {
        this.systemAuditService = systemAuditService;
    }

    @GetMapping
    public Page<SystemAuditEntity> list(@RequestParam(required = false) String username,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String resourceType,
                                        @RequestParam(required = false) String resourceId,
                                        Pageable pageable) {
        String orgId = CallerContext.resolveCallerOrgId();
        return systemAuditService.list(orgId, username, action, resourceType, resourceId, pageable);
    }
}
