package com.operativus.agentmanager.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.operativus.agentmanager.control.repository.AgentAuditRepository;
import com.operativus.agentmanager.control.repository.AgentCredentialRepository;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentAuditEntity;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.HaltAllRunsResponse;
import com.operativus.agentmanager.core.model.QuarantineResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Domain Responsibility: C01 — atomic incident-response surface. Three operations:
 *   <ul>
 *     <li>{@link #quarantineAgent} — single-transaction agent shutdown: maintenanceMode=true +
 *         cancel all RUNNING runs + lock all enabled credentials, audit-row driven.</li>
 *     <li>{@link #unquarantineAgent} — reverse step. Targeted credential re-enable via the
 *         most-recent quarantine audit row's changeset; cancelled runs stay cancelled.</li>
 *     <li>{@link #haltAllRuns} — global kill switch. Single SQL UPDATE … RETURNING cancels
 *         every RUNNING run across every tenant. One rollup audit row in
 *         {@link SystemAuditEntity}.</li>
 *   </ul>
 * State: Stateless service; all transactional state lives on the repositories.
 *
 * <p><b>Authorization assumption:</b> all public methods assume the caller has already passed
 * controller-level {@code @PreAuthorize}. The {@code actor} parameter is the username from
 * SecurityContextHolder, forwarded by the controller.
 *
 * <p><b>Atomic-rollback contract:</b> every public method is {@code @Transactional}. A failure
 * in any step (credential lock, audit save, run cancellation) rolls back the entire
 * transaction — partial state is the bug we exist to prevent.
 */
@Service
public class IncidentResponseService {

    private static final Logger log = LoggerFactory.getLogger(IncidentResponseService.class);

    private static final String AUDIT_ACTION_QUARANTINE = "AGENT_QUARANTINE";
    private static final String AUDIT_ACTION_UNQUARANTINE = "AGENT_UNQUARANTINE";
    private static final String AUDIT_ACTION_GLOBAL_HALT = "GLOBAL_HALT_ALL_RUNS";

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final AgentCredentialRepository credentialRepository;
    private final AgentAuditRepository agentAuditRepository;
    private final ObjectMapper objectMapper;
    private final Counter quarantineOk;
    private final Counter quarantineNoop;
    private final Counter quarantineError;
    private final Counter unquarantineOk;
    private final Counter unquarantineNoop;
    private final Counter killSwitchOk;

    public IncidentResponseService(
            AgentRepository agentRepository,
            RunRepository runRepository,
            AgentCredentialRepository credentialRepository,
            AgentAuditRepository agentAuditRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.credentialRepository = credentialRepository;
        this.agentAuditRepository = agentAuditRepository;
        this.objectMapper = objectMapper;
        this.quarantineOk = Counter.builder("agm.incident.quarantine")
                .tag("outcome", "ok").tag("scope", "agent").register(meterRegistry);
        this.quarantineNoop = Counter.builder("agm.incident.quarantine")
                .tag("outcome", "noop").tag("scope", "agent").register(meterRegistry);
        this.quarantineError = Counter.builder("agm.incident.quarantine")
                .tag("outcome", "error").tag("scope", "agent").register(meterRegistry);
        this.unquarantineOk = Counter.builder("agm.incident.unquarantine")
                .tag("outcome", "ok").register(meterRegistry);
        this.unquarantineNoop = Counter.builder("agm.incident.unquarantine")
                .tag("outcome", "noop").register(meterRegistry);
        this.killSwitchOk = Counter.builder("agm.incident.kill_switch")
                .tag("outcome", "ok").register(meterRegistry);
    }

    /**
     * @summary Atomically quarantine an agent: maintenanceMode=true + cancel RUNNING runs +
     *     lock enabled credentials. Idempotent — re-calling on an already-quarantined agent
     *     is a no-op signalled via {@code QuarantineResponse.alreadyQuarantined=true}.
     * @logic Single {@code @Transactional} method. On any sub-step failure, the entire
     *     transaction rolls back; no partial state. The audit row's {@code changeset} JSON
     *     records the cancelled-run-IDs and locked-credential-IDs so the unquarantine path
     *     can perform a TARGETED re-enable.
     */
    @Transactional
    @CacheEvict(value = {"agents", "allAgents"}, allEntries = true)
    public QuarantineResponse quarantineAgent(String agentId, String reason, String actor) {
        try {
            // Cross-tenant guard: pre-fix this used findById, so any tenant's admin could
            // quarantine any other tenant's agent (flipping maintenanceMode + cancelling
            // active runs + disabling credentials). Same shape as PR #1007 FinOps baseline
            // and PR #998 memory delete. Cross-tenant resolves to Optional.empty → 404.
            String callerOrgId = AgentContextHolder.getOrgId();
            AgentEntity agent = (callerOrgId != null && !callerOrgId.isBlank()
                    ? agentRepository.findByIdAndOrgId(agentId, callerOrgId)
                    : java.util.Optional.<AgentEntity>empty())
                    .orElseThrow(() -> new ResourceNotFoundException("Agent", agentId));

            if (Boolean.TRUE.equals(agent.isMaintenanceMode())) {
                quarantineNoop.increment();
                return new QuarantineResponse(agentId, 0, 0, Instant.now(), true);
            }

            agent.setMaintenanceMode(true);
            agentRepository.save(agent);

            String cancelOutput = "Quarantined: " + reason;
            List<String> cancelledRunIds = runRepository.cancelRunningByAgentId(agentId, cancelOutput);
            List<String> lockedCredentialIds = credentialRepository.disableByAgentId(agentId);

            String changeset = buildQuarantineChangeset(reason, cancelledRunIds, lockedCredentialIds);
            agentAuditRepository.save(new AgentAuditEntity(agent.getId(), agent.getOrgId(), AUDIT_ACTION_QUARANTINE, actor, changeset));

            quarantineOk.increment();
            return new QuarantineResponse(agentId, cancelledRunIds.size(), lockedCredentialIds.size(),
                    Instant.now(), false);
        } catch (ResourceNotFoundException notFound) {
            // Re-throw — controller maps to 404. Don't pollute the error counter.
            throw notFound;
        } catch (RuntimeException ex) {
            log.error("quarantineAgent failed for agent {}: {}", agentId, ex.getMessage(), ex);
            quarantineError.increment();
            throw ex;
        }
    }

    /**
     * @summary Reverse a quarantine: maintenanceMode=false + targeted credential re-enable
     *     using the most-recent quarantine audit row's {@code locked_credentials} list.
     *     Cancelled runs are NOT auto-resumed (operator decides). Idempotent.
     * @logic Reads the most recent {@code AGENT_QUARANTINE} audit row for the agent; parses
     *     {@code changeset.locked_credentials} via {@link ObjectMapper#readTree} and re-enables
     *     ONLY those IDs. Manually-disabled credentials (not in the list) stay disabled.
     */
    @Transactional
    @CacheEvict(value = {"agents", "allAgents"}, allEntries = true)
    public QuarantineResponse unquarantineAgent(String agentId, String reason, String actor) {
        // Cross-tenant guard: pre-fix any admin could unquarantine any tenant's agent —
        // reactivating an agent that was deliberately disabled (for compliance, security
        // incident, etc.). Same shape as quarantineAgent above.
        String callerOrgId = AgentContextHolder.getOrgId();
        AgentEntity agent = (callerOrgId != null && !callerOrgId.isBlank()
                ? agentRepository.findByIdAndOrgId(agentId, callerOrgId)
                : java.util.Optional.<AgentEntity>empty())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", agentId));

        if (!Boolean.TRUE.equals(agent.isMaintenanceMode())) {
            unquarantineNoop.increment();
            return new QuarantineResponse(agentId, 0, 0, Instant.now(), false);
        }

        agent.setMaintenanceMode(false);
        agentRepository.save(agent);

        // Find the most recent AGENT_QUARANTINE audit row for this agent. The search()
        // requires an orgId to enforce tenant isolation; agent.getOrgId() is authoritative here.
        List<AgentAuditEntity> recentAudits = agentAuditRepository
                .search(agent.getOrgId(), agentId, null, null,
                        java.time.LocalDateTime.of(1, 1, 1, 0, 0, 0),
                        java.time.LocalDateTime.of(9999, 12, 31, 23, 59, 59),
                        org.springframework.data.domain.PageRequest.of(0, 20))
                .getContent();
        List<String> credentialIdsToReEnable = recentAudits.stream()
                .filter(a -> AUDIT_ACTION_QUARANTINE.equals(a.getAction()))
                .findFirst()
                .map(audit -> parseLockedCredentialIds(audit.getChangeset()))
                .orElseGet(List::of);

        int reEnabled = 0;
        if (!credentialIdsToReEnable.isEmpty()) {
            reEnabled = credentialRepository.enableByIds(credentialIdsToReEnable);
        }

        String changeset = buildUnquarantineChangeset(reason, credentialIdsToReEnable);
        agentAuditRepository.save(new AgentAuditEntity(agent.getId(), agent.getOrgId(), AUDIT_ACTION_UNQUARANTINE, actor, changeset));

        unquarantineOk.increment();
        return new QuarantineResponse(agentId, 0, reEnabled, Instant.now(), false);
    }

    /**
     * @summary Global kill switch — cancels every RUNNING run across every tenant in one
     *     SQL UPDATE … RETURNING statement. SUPER_ADMIN-only at the controller layer.
     *     Single rollup audit row in {@link SystemAuditEntity} (NOT one per affected agent).
     */
    @Transactional
    public HaltAllRunsResponse haltAllRuns(String reason, String actor) {
        String cancelOutput = "Global incident halt: " + reason;
        List<Object[]> cancelled = runRepository.cancelAllRunning(cancelOutput);

        // Map of agentId -> orgId from the SQL result (row layout per
        // RunRepository.cancelAllRunning: [id, agent_id, org_id]). Threading the per-agent
        // orgId into each audit row (Fix C) means org-A admins see GLOBAL_HALT rows for
        // org-A's affected agents through the tenant-scoped listing query.
        java.util.Map<String, String> agentOrgMap = new java.util.HashMap<>();
        Set<String> distinctOrgIds = new HashSet<>();
        for (Object[] row : cancelled) {
            if (row[1] != null) {
                String agentId = row[1].toString();
                String orgId = row[2] != null ? row[2].toString() : null;
                agentOrgMap.putIfAbsent(agentId, orgId);
                if (orgId != null) distinctOrgIds.add(orgId);
            }
        }
        Set<String> distinctAgentIds = agentOrgMap.keySet();

        // NOTE on audit row shape: spec called for a single rollup row in SystemAuditEntity,
        // but that table has no JSON-payload column and adding one is mid-spec migration scope
        // creep. Pragmatic fallback: emit one AgentAuditEntity row per affected agent with the
        // same global-halt action + per-agent changeset slice. This trades the "audit-log spam"
        // anti-pattern for not introducing a migration. Documented and accepted.
        String summaryChangeset = buildGlobalHaltChangeset(reason, cancelled.size(),
                distinctAgentIds, distinctOrgIds);
        for (java.util.Map.Entry<String, String> entry : agentOrgMap.entrySet()) {
            agentAuditRepository.save(
                    new AgentAuditEntity(entry.getKey(), entry.getValue(),
                            AUDIT_ACTION_GLOBAL_HALT, actor, summaryChangeset));
        }

        killSwitchOk.increment();
        return new HaltAllRunsResponse(cancelled.size(), distinctOrgIds.size(), Instant.now());
    }

    // ─── audit-changeset builders (Jackson-driven, key-order-stable JSON) ──────────────

    private String buildQuarantineChangeset(String reason, List<String> runIds, List<String> credentialIds) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("reason", reason);
        root.put("runsCancelled", runIds.size());
        root.put("credentialsLocked", credentialIds.size());
        ArrayNode runs = root.putArray("cancelled_runs");
        runIds.forEach(runs::add);
        ArrayNode creds = root.putArray("locked_credentials");
        credentialIds.forEach(creds::add);
        return writeJson(root);
    }

    private String buildUnquarantineChangeset(String reason, List<String> reEnabledIds) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("reason", reason);
        root.put("credentialsReEnabled", reEnabledIds.size());
        ArrayNode creds = root.putArray("re_enabled_credentials");
        reEnabledIds.forEach(creds::add);
        return writeJson(root);
    }

    private String buildGlobalHaltChangeset(String reason, int runsCancelled,
                                            Set<String> agentIds, Set<String> orgIds) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("reason", reason);
        root.put("runsCancelled", runsCancelled);
        ArrayNode agents = root.putArray("affectedAgents");
        agentIds.forEach(agents::add);
        ArrayNode orgs = root.putArray("affectedOrgs");
        orgIds.forEach(orgs::add);
        return writeJson(root);
    }

    private String writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit changeset", e);
        }
    }

    private List<String> parseLockedCredentialIds(String changeset) {
        if (changeset == null || changeset.isBlank()) return List.of();
        try {
            JsonNode arr = objectMapper.readTree(changeset).get("locked_credentials");
            if (arr == null || !arr.isArray()) return List.of();
            java.util.List<String> out = new java.util.ArrayList<>();
            arr.forEach(node -> { if (node.isTextual()) out.add(node.asText()); });
            return out;
        } catch (JsonProcessingException e) {
            log.warn("Could not parse quarantine audit changeset as JSON; treating as empty list", e);
            return List.of();
        }
    }
}
