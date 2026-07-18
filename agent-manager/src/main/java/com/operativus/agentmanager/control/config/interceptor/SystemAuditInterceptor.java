package com.operativus.agentmanager.control.config.interceptor;

import com.operativus.agentmanager.control.service.SystemAuditService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.SecurityPrincipals;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Domain Responsibility: Captures a single {@code system_audits} row after every successful
 *   HTTP mutation (POST/PUT/PATCH/DELETE with a 2xx status) against a non-agent admin/resource
 *   controller. Runs in {@code afterCompletion}, after the response has committed, so the audit
 *   row reflects the actual outcome including exceptions-translated-to-error-responses.
 *
 *   <p>Complementary, not replacement, for
 *   {@link com.operativus.agentmanager.control.service.AgentAdminService#logAudit} — agent CRUD
 *   keeps its in-service write into {@code agent_audits} because that table carries
 *   agent-specific state (versionNumber, changeset JSON of the full definition) that a URL-level
 *   capture can't see. Paths under {@code /api/admin/agents/**} are therefore skipped here.</p>
 *
 *   <p>Auth events (login success/failure/logout) are written directly from
 *   {@link com.operativus.agentmanager.control.controller.AuthController} rather than through
 *   this interceptor so the failure branch (which throws {@code AuthenticationException} before
 *   a handler is selected) is also captured.</p>
 *
 * State: Stateless
 */
@Component
public class SystemAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SystemAuditInterceptor.class);

    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Paths whose mutations are recorded elsewhere or are read-only — skip to avoid double-counting. */
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/api/admin/agents",            // Covered by AgentAdminService.logAudit → agent_audits.
            "/api/admin/audit-logs",        // Read-only list.
            "/api/admin/system-audit-logs"  // Read-only list.
    );

    /**
     * Explicit URL segment → resource-type mapping. Keeps the audit vocabulary deterministic
     * instead of leaking URL-pluralization choices (e.g. "knowledge-bases") into the
     * {@code resource_type} column.
     */
    private static final Map<String, String> RESOURCE_TYPE_BY_SEGMENT = Map.ofEntries(
            Map.entry("users", "USER"),
            Map.entry("models", "MODEL"),
            Map.entry("knowledge-bases", "KNOWLEDGE_BASE"),
            Map.entry("teams", "TEAM"),
            Map.entry("schedules", "SCHEDULE"),
            Map.entry("workflows", "WORKFLOW"),
            Map.entry("evaluations", "EVALUATION"),
            Map.entry("approvals", "APPROVAL"),
            Map.entry("memory", "MEMORY"),
            Map.entry("memories", "MEMORY"),
            Map.entry("tools", "TOOL"),
            Map.entry("jobs", "JOB"),
            Map.entry("alert-rules", "ALERT_RULE"),
            Map.entry("alert-integrations", "ALERT_INTEGRATION"),
            Map.entry("mcp-servers", "MCP_SERVER"),
            Map.entry("mcp", "MCP_SERVER"),
            Map.entry("agent-credentials", "AGENT_CREDENTIAL"),
            Map.entry("extensions", "EXTENSION"),
            Map.entry("compliance", "COMPLIANCE"),
            Map.entry("registry", "REGISTRY")
    );

    private final SystemAuditService auditService;

    public SystemAuditInterceptor(SystemAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        String method = request.getMethod();
        if (!MUTATION_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return;
        }

        String path = request.getRequestURI();
        if (path == null) {
            return;
        }
        for (String skip : SKIP_PREFIXES) {
            if (path.equals(skip) || path.startsWith(skip + "/")) {
                return;
            }
        }

        int status = response.getStatus();
        // Only capture successful mutations. 4xx/5xx rejections leave no row — matches matrix §24
        // case 1 ("every successful controller mutation writes exactly one audit row").
        if (status < 200 || status >= 300) {
            return;
        }

        String resourceType = resolveResourceType(path);
        if (resourceType == null) {
            // Unmapped path — don't flood the audit table with noise from endpoints we haven't
            // explicitly opted in. Log once at DEBUG so surfacing a new mutation-capable path
            // during development is visible.
            log.debug("system_audit skipped (unmapped path): {} {} -> {}", method, path, status);
            return;
        }

        String action = resolveAction(method);
        String resourceId = resolveResourceId(request, path);
        String username = resolveUsername();
        String orgId = AgentContextHolder.getOrgId();

        auditService.record(orgId, username, action, resourceType, resourceId, method, path, status);
    }

    private static String resolveResourceType(String path) {
        // Strip /api/ prefix and drop the optional version + "admin/" segments. The {@code v1/}
        // prefix appears on a growing share of controllers (`/api/v1/knowledge-bases`,
        // `/api/v1/teams`, etc.) — without this strip those paths surface as resource_type=null
        // and never get audited despite being mutation-capable.
        if (!path.startsWith("/api/")) {
            return null;
        }
        String trimmed = path.substring("/api/".length());
        if (trimmed.startsWith("v1/")) {
            trimmed = trimmed.substring("v1/".length());
        }
        if (trimmed.startsWith("admin/")) {
            trimmed = trimmed.substring("admin/".length());
        }
        int slash = trimmed.indexOf('/');
        String head = (slash < 0) ? trimmed : trimmed.substring(0, slash);
        if (head.isEmpty()) {
            return null;
        }
        return RESOURCE_TYPE_BY_SEGMENT.get(head);
    }

    private static String resolveAction(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> method.toUpperCase(Locale.ROOT);
        };
    }

    @SuppressWarnings("unchecked")
    private static String resolveResourceId(HttpServletRequest request, String path) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attr instanceof Map<?, ?> vars) {
            Object id = ((Map<String, ?>) vars).get("id");
            if (id != null) {
                return id.toString();
            }
            // Fall back to the first path variable whose name ends in "Id".
            for (Map.Entry<?, ?> e : vars.entrySet()) {
                if (e.getKey() instanceof String key && key.toLowerCase(Locale.ROOT).endsWith("id") && e.getValue() != null) {
                    return e.getValue().toString();
                }
            }
        }
        return null;
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() != null
                && !SecurityPrincipals.ANONYMOUS_USER.equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return null;
    }
}
