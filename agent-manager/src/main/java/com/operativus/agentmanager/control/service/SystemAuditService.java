package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.SystemAuditRepository;
import com.operativus.agentmanager.core.entity.SystemAuditEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain Responsibility: Single write and read path for the generalized {@code system_audits}
 *   table. Called from (a) {@link com.operativus.agentmanager.control.config.interceptor.SystemAuditInterceptor}
 *   after every successful non-agent HTTP mutation, (b) {@link com.operativus.agentmanager.control.controller.AuthController}
 *   on login success/failure/logout, and (c)
 *   {@link com.operativus.agentmanager.control.controller.SystemAuditLogController} for the admin
 *   read surface at {@code /api/admin/system-audit-logs}.
 *
 *   <p>Writes are {@code Propagation.REQUIRES_NEW} so an audit-log insert never rolls back with the
 *   caller's business transaction — a post-handle interceptor running after response commit must
 *   still land a row; conversely, a failed audit insert must not poison the caller's commit.</p>
 *
 * State: Stateless
 */
@Service
public class SystemAuditService {

    private static final Logger log = LoggerFactory.getLogger(SystemAuditService.class);

    private final SystemAuditRepository repository;

    public SystemAuditService(SystemAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes a single audit row. Failures are logged and swallowed — audit is observability, not
     * authoritative state; a storage hiccup must not break the business path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String orgId,
                       String username,
                       String action,
                       String resourceType,
                       String resourceId,
                       String httpMethod,
                       String requestPath,
                       Integer responseStatus) {
        try {
            SystemAuditEntity row = new SystemAuditEntity();
            row.setOrgId(orgId);
            row.setUsername(username);
            row.setAction(action);
            row.setResourceType(resourceType);
            row.setResourceId(resourceId);
            row.setHttpMethod(httpMethod);
            row.setRequestPath(requestPath);
            row.setResponseStatus(responseStatus);
            repository.save(row);
            log.trace("system_audit persisted: org={} user={} action={} resource={}/{} {} {} -> {}",
                    orgId, username, action, resourceType, resourceId, httpMethod, requestPath, responseStatus);
        } catch (Exception e) {
            log.warn("Failed to persist system_audit row (action={} resource={}/{}): {}",
                    action, resourceType, resourceId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<SystemAuditEntity> list(String orgId,
                                        String username,
                                        String action,
                                        String resourceType,
                                        String resourceId,
                                        Pageable pageable) {
        return repository.search(
                blankToNull(orgId),
                blankToNull(username),
                blankToNull(action),
                blankToNull(resourceType),
                blankToNull(resourceId),
                pageable);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
