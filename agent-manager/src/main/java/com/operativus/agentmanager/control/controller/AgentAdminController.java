package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.core.entity.AgentAuditEntity;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.registry.AgentAdminOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;

import java.util.List;

/**
 * Domain Responsibility: REST API boundary for administering and configuring Agent definitions and retrieving historical run datasets.
 *
 * <p><b>Authz:</b> All endpoints require {@code ROLE_ADMIN}. Path is {@code /api/admin/agents},
 * class is named {@code AgentAdminController}, and {@link #getAgent} javadoc already claimed
 * "ADMIN-only via class-level gate" — but the annotation itself was missing until PR #969.
 * Service layer is tenant-scoped via {@code callerOrgId()}, but tenant-scoping is not the
 * same as admin-only: every authenticated user in the org could previously create / update /
 * delete / import / rollback agent definitions shared within their org.
 *
 * <p><b>Known follow-ups not in this PR:</b>
 * <ul>
 *   <li>{@code cancelRun} has no ownership check in the service layer — any user (now any
 *       admin) can cancel any run by ID. Pre-fix this was cross-tenant exploitable; post-fix
 *       it's narrowed to admins but cross-tenant within the admin role is still possible.</li>
 *   <li>{@code RunsController} has no user-side cancel endpoint — non-admin users have no
 *       way to cancel their own runs. {@code SessionDetailsPage} currently calls
 *       {@code AgentAdminApi.cancelRun}; this will start returning 403 for non-admins after
 *       this PR until the user-side path lands.</li>
 * </ul>
 *
 * State: Stateless
 */
@RestController
@RequestMapping("/api/admin/agents")
@PreAuthorize("hasRole('ADMIN')")
public class AgentAdminController {

    private static final Logger log = LoggerFactory.getLogger(AgentAdminController.class);

    private final AgentAdminOperations agentAdminService;

    public AgentAdminController(AgentAdminOperations agentAdminService) {
        this.agentAdminService = agentAdminService;
    }

    /**
     * @summary Retrieves a paginated list of all Agent definitions.
     * @logic
     * - Logs the request with includeInactive flag.
     * - Delegates to AgentAdminService.getAllAgents.
     * - Wraps the result in a ResponseEntity.
     */
    @GetMapping
    public ResponseEntity<Page<AgentDefinition>> getAllAgents(
            @org.springframework.data.web.PageableDefault(sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        log.debug("Fetching all agents (includeInactive={})", includeInactive);
        return ResponseEntity.ok(agentAdminService.getAllAgents(pageable, includeInactive));
    }

    /**
     * @summary Fetches a single Agent definition by ID. ADMIN-only via class-level gate.
     * @logic Delegates to AgentAdminService.getAgent; 404 on missing or cross-tenant via
     *     ResourceNotFoundException + GlobalExceptionHandler.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AgentDefinition> getAgent(@PathVariable String id) {
        log.debug("Fetching agent: {}", id);
        return ResponseEntity.ok(agentAdminService.getAgent(id));
    }

    /**
     * @summary Creates a new Agent definition.
     * @logic
     * - Validates the incoming AgentDefinition DTO.
     * - Logs the creation intent.
     * - Delegates to AgentAdminService.createAgent.
     * - Returns a 201 Created response.
     */
    @PostMapping
    public ResponseEntity<AgentDefinition> createAgent(@Valid @RequestBody AgentDefinition dto) {
        log.info("Creating new agent definition with ID: {}", dto.id());
        AgentDefinition created = agentAdminService.createAgent(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * @summary Updates an existing Agent definition.
     * @logic
     * - Validates the modified AgentDefinition DTO.
     * - Delegates to AgentAdminService to apply updates.
     * - Wraps the result in a ResponseEntity.
     */
    @PutMapping("/{id}")
    public ResponseEntity<AgentDefinition> updateAgent(
            @PathVariable String id,
            @Valid @RequestBody AgentDefinition dto) {
        // The body's agentId field (mapped to dto.id()) was previously dropped silently
        // when it disagreed with the path. Reject the mismatch up front so callers learn
        // the contract instead of debugging "why didn't my rename take effect?".
        if (dto.id() != null && !dto.id().isBlank() && !dto.id().equals(id)) {
            throw new BusinessValidationException(
                    "Request body agentId '" + dto.id() + "' does not match path id '" + id + "'.");
        }
        return ResponseEntity.ok(agentAdminService.updateAgent(id, dto));
    }

    /**
     * @summary Deletes an Agent definition by ID.
     * @logic
     * - Logs the deletion intent.
     * - Delegates to AgentAdminService to perform a soft or hard delete.
     * - Returns 204 No Content.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String id) {
        log.debug("REST request to delete Agent ID: {}", id);
        agentAdminService.deleteAgent(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary Restores a soft-deleted Agent definition.
     * @logic
     * - Delegates to AgentAdminService.restoreAgent.
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restoreAgent(@PathVariable String id) {
        log.debug("REST request to restore Agent ID: {}", id);
        agentAdminService.restoreAgent(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @summary Exports an Agent definition for external backup or import.
     * @logic
     * - Delegates to AgentAdminService to fetch the exportable definition.
     * - Wraps the result in a ResponseEntity.
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<AgentDefinition> exportAgent(@PathVariable String id) {
        return ResponseEntity.ok(agentAdminService.exportAgent(id));
    }

    /**
     * @summary Imports an external Agent definition into the system.
     * @logic
     * - Validates the incoming DTO.
     * - Delegates to AgentAdminService to persist the imported definition.
     * - Wraps the result in a ResponseEntity.
     */
    @PostMapping("/import")
    public ResponseEntity<AgentDefinition> importAgent(@Valid @RequestBody AgentDefinition dto) {
        AgentDefinition imported = agentAdminService.importAgent(dto);
        return ResponseEntity.ok(imported);
    }

    /**
     * @summary Retrieves the multi-agent hierarchy topology for a given Team agent.
     * @logic
     * - Delegates to AgentAdminService to compute and return the topology map.
     * - Wraps the result in a ResponseEntity.
     */
    @GetMapping("/{id}/topology")
    public ResponseEntity<com.operativus.agentmanager.core.model.TopologyDTO> getAgentTopology(@PathVariable String id) {
        return ResponseEntity.ok(agentAdminService.getAgentTopology(id));
    }

    /**
     * @summary Retrieves the paginated execution run history of a specific agent.
     * @logic
     * - Delegates to AgentAdminService to fetch historical AgentRun records.
     * - Wraps the result in a ResponseEntity.
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<Page<AgentRun>> getAgentHistory(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(agentAdminService.getAgentHistory(id, PageRequest.of(page, size)));
    }

    /**
     * @summary Retrieves developer-centric logs or traces for a specific agent.
     * @logic
     * - Delegates to AgentAdminService to fetch logs.
     * - Wraps the result in a ResponseEntity.
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<List<String>> getAgentLogs(@PathVariable String id) {
        return ResponseEntity.ok(agentAdminService.getAgentLogs(id));
    }

    /**
     * @summary Retrieves the paginated configuration audit history (Revisions) for an agent.
     * @logic
     * - Delegates to AgentAdminService to fetch Envers/Hibernate audit entities.
     * - Wraps the result in a ResponseEntity.
     */
    @GetMapping("/{id}/audit")
    public ResponseEntity<Page<AgentAuditEntity>> getAgentAuditHistory(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(agentAdminService.getAgentAuditHistory(id, PageRequest.of(page, size)));
    }

    /**
     * @summary Lists all version snapshots for an agent.
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<AgentDefinition>> getAgentVersions(@PathVariable String id) {
        return ResponseEntity.ok(agentAdminService.getAgentVersions(id));
    }

    /**
     * @summary Rolls back an agent to the configuration stored in a specific audit snapshot.
     */
    @PostMapping("/{id}/rollback/{auditId}")
    public ResponseEntity<AgentDefinition> rollbackAgent(
            @PathVariable String id,
            @PathVariable String auditId) {
        log.info("Rolling back agent {} to audit snapshot {}", id, auditId);
        return ResponseEntity.ok(agentAdminService.rollbackAgent(id, auditId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Void> cancelRun(@PathVariable("runId") String runId) {
        agentAdminService.cancelRun(runId);
        return ResponseEntity.noContent().build();
    }
}
