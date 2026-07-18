package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.operativus.agentmanager.core.entity.AgentAuditEntity;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.control.repository.AgentAuditRepository;
import com.operativus.agentmanager.control.repository.EvaluationRepository;
import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.model.DeveloperMetricsDTO;
import com.operativus.agentmanager.control.repository.TransitionEdgeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.StaleDataException;

import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.enums.AuditActionType;
import com.operativus.agentmanager.core.registry.AgentAdminOperations;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.TenantConstants;

/**
 * Domain Responsibility: Core management service handling the CRUD lifecycle and administrative querying of Agent Definitions.
 * State: Stateless
 */
@Service
@Validated
public class AgentAdminService implements AgentAdminOperations {
    
    private static final Logger log = LoggerFactory.getLogger(AgentAdminService.class);

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
    }

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;
    private final AgentAuditRepository auditRepository;
    private final EvaluationRepository evaluationRepository;
    private final TransitionEdgeRepository transitionEdgeRepository;
    private final ObjectMapper objectMapper;
    private final AgentOperations agentOperations;

    public AgentAdminService(AgentRepository agentRepository, RunRepository runRepository, AgentAuditRepository auditRepository, EvaluationRepository evaluationRepository, TransitionEdgeRepository transitionEdgeRepository, ObjectMapper objectMapper, AgentOperations agentOperations) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.auditRepository = auditRepository;
        this.evaluationRepository = evaluationRepository;
        this.transitionEdgeRepository = transitionEdgeRepository;
        this.objectMapper = objectMapper;
        this.agentOperations = agentOperations;
    }

    /**
     * @summary Retrieves a paginated list of all Agent definitions.
     * @logic Queries the AgentRepository based on the inclusion flag. Maps the resulting AgentEntity Page to AgentDefinition DTOs.
     */
    @Transactional(readOnly = true)
    public Page<AgentDefinition> getAllAgents(Pageable pageable, boolean includeInactive) {
        log.debug("Fetching all agents. Pagination: {}, Include inactive: {}", pageable, includeInactive);
        String orgId = callerOrgId();
        Page<AgentEntity> page;
        if (includeInactive) {
            page = agentRepository.findAllByOrgId(orgId, pageable);
        } else {
            page = agentRepository.findAllByOrgIdAndActive(orgId, true, pageable);
        }
        return page.map(this::mapToDefinition);
    }

    /**
     * @summary Retrieves a specific Agent definition by its unique identifier.
     * @logic Queries the AgentRepository by ID, maps the found entity to a DTO, and throws a ResourceNotFoundException if missing.
     */
    @Transactional(readOnly = true)
    public AgentDefinition getAgent(String id) {
        log.debug("Fetching agent details for ID: {}", id);
        return agentRepository.findByIdAndOrgId(id, callerOrgId())
                .map(this::mapToDefinition)
                .orElseThrow(() -> {
                    log.warn("Agent not found with ID: {}", id);
                    return new ResourceNotFoundException("Agent", id);
                });
    }

    /**
     * @summary Retrieves all versions of a specific Agent definition.
     * @logic Currenly returns just the latest version as versioning is not yet fully implemented.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AgentDefinition> getAgentVersions(String id) {
        log.debug("Fetching agent versions for ID: {}", id);
        return List.of(getAgent(id));
    }

    /**
     * @summary Creates and persists a new Agent definition.
     * @logic Maps the incoming DTO to an AgentEntity, generates a new UUID if missing, sets it to active by default, saves it to the repository, logs the creation in the audit log, and publishes an AgentCreatedEvent.
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"agents", "allAgents"}, allEntries = true)
    public AgentDefinition createAgent(AgentDefinition dto) {
        log.info("Creating new agent: {}", dto.name());
        AgentEntity entity = mapFromDefinition(dto);
        if (entity.getId() == null || entity.getId().isEmpty()) {
            entity.setId(java.util.UUID.randomUUID().toString());
        }
        entity.setVersion(null); // @Version=0 makes Spring Data treat as detached → merge → 409 on fresh ID.
        // Ensure new agents are active by default
        entity.setActive(true);
        entity.setOrgId(callerOrgId());
        
        log.debug("--- Save (Create) Agent Tools Check ---");
        log.debug("Incoming DTO tools array: {}", dto.tools());
        log.debug("Entity tools collection (After mapping): {}", entity.getTools());
        
        entity = agentRepository.save(entity);
        log.debug("Agent created successfully with ID: {}", entity.getId());
        logAudit(entity, AuditActionType.CREATE, dto);
        
        AgentDefinition savedDto = mapToDefinition(entity);
        return savedDto;
    }

    /**
     * @summary Updates properties of an existing Agent definition.
     * @logic Fetches the existing entity by ID, maps all mutable fields from the DTO, saves the entity catching optimistic locking failures, logs the update in the audit log, and publishes an AgentUpdatedEvent.
     */
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(value = {"agents", "allAgents"}, allEntries = true)
    public AgentDefinition updateAgent(String id, AgentDefinition dto) {
        log.info("Updating agent ID: {}", id);
        AgentEntity entity = agentRepository.findByIdAndOrgId(id, callerOrgId())
                .orElseThrow(() -> {
                    log.warn("Failed to update: Agent not found with ID {}", id);
                    return new ResourceNotFoundException("Agent", id);
                });
        
        // Update fields
        entity.setName(dto.name());
        entity.setDescription(dto.description());
        entity.setCapabilities(dto.capabilities() == null ? new String[0] : dto.capabilities().toArray(new String[0]));
        entity.setInstructions(dto.instructions());
        entity.setModelId(dto.modelId());
        entity.setContextWindowSize(dto.contextWindowSize());
        entity.setMemoryEnabled(dto.memoryEnabled());
        entity.setAddHistoryToMessages(dto.addHistoryToMessages());
        entity.setTools(dto.tools() != null ? new java.util.ArrayList<>(dto.tools()) : new java.util.ArrayList<>());
        
        entity.setReasoningEnabled(dto.monitoringEnabled());
        entity.setTeam(dto.isTeam());
        entity.setTeamMode(dto.teamMode());
        entity.setMembers(dto.members());
        entity.setAllowedRoles(dto.allowedRoles());
        entity.setMarkdownDocs(dto.markdownDocs());
        entity.setSupportChannel(dto.supportChannel());
        entity.setPrimaryOwner(dto.primaryOwner());
        entity.setSupportedLocales(dto.supportedLocales());
        entity.setAccessibilityCompatibility(dto.accessibilityCompatibility());
        entity.setTrainingDatasets(dto.trainingDatasets());
        entity.setKnowledgeBaseIds(dto.knowledgeBaseIds());
        entity.setRequiresPiiRedaction(dto.requiresPiiRedaction());
        entity.setApprovedForProduction(dto.approvedForProduction());
        entity.setMaintenanceMode(dto.maintenanceMode());
        entity.setActive(dto.active());
        entity.setEnforceJsonOutput(dto.enforceJsonOutput());
        entity.setConfiguration(dto.configuration());
        entity.setSecurityTier(dto.securityTier() != null ? dto.securityTier() : 1);
        entity.setComplianceTier(dto.complianceTier() != null ? dto.complianceTier() : com.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD);
        entity.setTemperature(dto.temperature());
        entity.setTopP(dto.topP());
        entity.setFrequencyPenalty(dto.frequencyPenalty());
        entity.setSystemPromptMode(dto.systemPromptMode());
        entity.setMaxConcurrentExecutions(dto.maxConcurrentExecutions());
        entity.setFinOpsTokenBudget(dto.finOpsTokenBudget());
        entity.setFinOpsRiskTier(dto.finOpsRiskTier());
        entity.setCompressionThreshold(dto.compressionThreshold());
        entity.setSummarizationThreshold(dto.summarizationThreshold());
        entity.setOptimizationModelId(dto.optimizationModelId());
        entity.setPreHooks(dto.preHooks() != null ? new java.util.ArrayList<>(dto.preHooks()) : new java.util.ArrayList<>());
        entity.setPostHooks(dto.postHooks() != null ? new java.util.ArrayList<>(dto.postHooks()) : new java.util.ArrayList<>());
        // REQ-HR-1 — was missing from updateAgent; AgentAdminService.mapFromDefinition
        // (create path) sets it but the manual update map didn't. Surfaced live when an
        // operator PUT humanReview through admin and the gate never fired — value
        // silently dropped between DTO and DB.
        entity.setHumanReview(dto.humanReview());
        // gap #8 — same drift as humanReview. fallbackModelIds set on create but missed
        // on update.
        entity.setFallbackModelIds(dto.fallbackModelIds() != null
                ? new java.util.ArrayList<>(dto.fallbackModelIds())
                : null);

        log.debug("--- Save Agent Tools Check ---");
        log.debug("Incoming DTO tools array: {}", dto.tools());
        log.debug("Entity tools collection (After mapping): {}", entity.getTools());

        try {
            entity = agentRepository.save(entity);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic locking failure on agent update: {}", id);
            throw new StaleDataException("Agent", id);
        }
        logAudit(entity, AuditActionType.UPDATE, dto);
        
        AgentDefinition updatedDto = mapToDefinition(entity);
        return updatedDto;
    }

    /**
     * @summary Performs a soft-delete on an Agent definition.
     * @logic Validates active or blocked run counts to prevent deleting in-use agents, marks the entity as inactive, saves it, logs the deletion, and publishes an AgentDeletedEvent.
     */
    @Transactional
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'delete')")
    @org.springframework.cache.annotation.CacheEvict(value = {"agents", "allAgents"}, allEntries = true)
    public void deleteAgent(String id) {
        log.info("Deleting agent ID: {}", id);
        AgentEntity entity = agentRepository.findByIdAndOrgId(id, callerOrgId())
                .orElseThrow(() -> {
                    log.warn("Failed to delete: Agent not found with ID {}", id);
                    return new ResourceNotFoundException("Agent", id);
                });
                
        // Lifecycle validation
        long activeRuns = runRepository.countByAgentIdAndStatus(id, RunStatus.RUNNING);
        long blockedRuns = runRepository.countByAgentIdAndStatus(id, RunStatus.PAUSED);
        if (activeRuns > 0 || blockedRuns > 0) {
            throw new BusinessValidationException("Cannot delete an agent with active or blocked runs.");
        }
        
        // Soft delete
        entity.setActive(false);
        agentRepository.save(entity);
        log.debug("Agent ID {} successfully marked as inactive (soft-delete)", id);
        logAudit(entity, AuditActionType.DELETE, null);
    }

    /**
     * @summary Restores a soft-deleted Agent definition.
     * @logic Marks the existing entity as active, saves it, and logs the restoration in the audit log.
     */
    @Transactional
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'restore')")
    @org.springframework.cache.annotation.CacheEvict(value = {"agents", "allAgents"}, allEntries = true)
    public void restoreAgent(String id) {
        log.info("Restoring agent ID: {}", id);
        AgentEntity entity = agentRepository.findByIdAndOrgId(id, callerOrgId())
                .orElseThrow(() -> {
                    log.warn("Failed to restore: Agent not found with ID {}", id);
                    return new ResourceNotFoundException("Agent", id);
                });
        entity.setActive(true);
        agentRepository.save(entity);
        log.debug("Agent ID {} successfully restored", id);
        logAudit(entity, AuditActionType.RESTORE, null);
    }

    /**
     * @summary Retrieves the paginated execution run history of a specific agent.
     * @logic Validates the agent ID existence and queries the RunRepository for historical runs ordered by creation date.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'read')")
    public Page<AgentRun> getAgentHistory(String id, Pageable pageable) {
        if (!agentRepository.existsByIdAndOrgId(id, callerOrgId())) {
            throw new ResourceNotFoundException("Agent", id);
        }
        return runRepository.findByAgentIdOrderByCreatedAtDesc(id, pageable);
    }

    /**
     * @summary Retrieves developer-centric logs or traces for a specific agent.
     * @logic Validates the agent ID existence and dynamically maps the top 10 most recent execution runs into human-readable log formats.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'read')")
    public List<String> getAgentLogs(String id) {
        if (!agentRepository.existsByIdAndOrgId(id, callerOrgId())) {
            throw new ResourceNotFoundException("Agent", id);
        }

        Page<AgentRun> recentRuns = runRepository.findByAgentIdOrderByCreatedAtDesc(id, org.springframework.data.domain.PageRequest.of(0, 10));
        
        if (recentRuns.isEmpty()) {
            return List.of("[INFO] Agent " + id + " initialized.", "[DEBUG] Waiting for execution requests...");
        }

        return recentRuns.stream()
                .map(run -> String.format("[%s] [RUN: %s] Status: %s | Tokens/Chars Output: %d",
                        run.getCreatedAt(),
                        run.getId(),
                        run.getStatus(),
                        run.getOutput() != null ? run.getOutput().length() : 0))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * @summary Retrieves the paginated configuration audit history (Revisions) for an agent.
     * @logic Validates the agent ID existence and queries the AgentAuditRepository for historical changes.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'read')")
    public Page<AgentAuditEntity> getAgentAuditHistory(String id, Pageable pageable) {
        if (!agentRepository.existsByIdAndOrgId(id, callerOrgId())) {
            throw new ResourceNotFoundException("Agent", id);
        }
        return auditRepository.search(callerOrgId(), id, null, null,
                java.time.LocalDateTime.of(1, 1, 1, 0, 0, 0),
                java.time.LocalDateTime.of(9999, 12, 31, 23, 59, 59),
                pageable);
    }

    /**
     * @summary Exports an Agent definition for external backup or import.
     * @logic Delegates to getAgent() to retrieve the DTO.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'read')")
    public AgentDefinition exportAgent(String id) {
        return getAgent(id);
    }

    /**
     * @summary Imports an external Agent definition into the system.
     * @logic Validates required fields, maps the DTO to an entity, saves it to the AgentRepository, and logs the IMPORT action.
     */
    @Transactional
    @PreAuthorize("hasPermission(null, 'AgentDefinition', 'create')")
    public AgentDefinition importAgent(AgentDefinition definition) {
        if (definition == null || definition.id() == null) {
            throw new BusinessValidationException("Invalid agent definition: Missing required fields");
        }
        AgentEntity entity = mapFromDefinition(definition);
        entity.setVersion(null);
        entity.setOrgId(callerOrgId());
        entity = agentRepository.save(entity);
        logAudit(entity, AuditActionType.IMPORT, definition);
        return mapToDefinition(entity);
    }

    /**
     * @summary Retrieves the multi-agent hierarchy topology for a given agent.
     * @logic Fetches the root AgentEntity, iterates over configured tools and team members to add them as child nodes, and constructs a unified TopologyDTO with nodes and edges.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'read')")
    public com.operativus.agentmanager.core.model.TopologyDTO getAgentTopology(String id) {
        AgentEntity entity = agentRepository.findByIdAndOrgId(id, callerOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", id));

        List<com.operativus.agentmanager.core.model.TopologyDTO.Node> nodes = new java.util.ArrayList<>();
        List<com.operativus.agentmanager.core.model.TopologyDTO.Edge> edges = new java.util.ArrayList<>();
        
        // Root node
        nodes.add(new com.operativus.agentmanager.core.model.TopologyDTO.Node(entity.getId(), entity.getName(), "agent"));
        
        // Tools
        if (entity.getTools() != null) {
            for (String tool : entity.getTools()) {
                String toolNodeId = "tool_" + tool;
                nodes.add(new com.operativus.agentmanager.core.model.TopologyDTO.Node(toolNodeId, tool, "tool"));
                edges.add(new com.operativus.agentmanager.core.model.TopologyDTO.Edge(entity.getId() + "_" + toolNodeId, entity.getId(), toolNodeId));
            }
        }
        
        // Team Members
        if (entity.isTeam() && entity.getMembers() != null) {
            for (String memberId : entity.getMembers()) {
                agentRepository.findById(memberId).ifPresent(member -> {
                    nodes.add(new com.operativus.agentmanager.core.model.TopologyDTO.Node(member.getId(), member.getName(), "member"));
                    edges.add(new com.operativus.agentmanager.core.model.TopologyDTO.Edge(entity.getId() + "_" + member.getId(), entity.getId(), member.getId()));
                });
            }
        }
        
        // Transition edges (DAG constraints)
        List<com.operativus.agentmanager.core.model.TopologyDTO.TransitionConstraint> transitionConstraints =
            transitionEdgeRepository.findByTeamId(id).stream()
                .map(edge -> new com.operativus.agentmanager.core.model.TopologyDTO.TransitionConstraint(
                        edge.getId(), edge.getSourceAgentId(), edge.getTargetAgentId()))
                .collect(java.util.stream.Collectors.toList());
        
        return new com.operativus.agentmanager.core.model.TopologyDTO(nodes, edges, transitionConstraints);
    }

    /**
     * @summary Generates developer experience (DX) and performance metrics for a specific agent.
     * @logic Calculates a testability score based on attached Evaluation records and computes a maintainability grade based on tools and configuration complexity.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'read')")
    public DeveloperMetricsDTO getDeveloperMetrics(String id) {
        AgentEntity entity = agentRepository.findByIdAndOrgId(id, callerOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Agent", id));

        long evaluationCount = evaluationRepository.findByAgentId(id).size();
        
        // Calculate Testability Score based on evaluation coverage
        double testabilityScore = Math.min(100.0, evaluationCount * 10.0);
        
        // Calculate Maintainability Grade based on configuration complexity
        int complexityFactor = 0;
        if (entity.getTools() != null) complexityFactor += entity.getTools().size();
        if (entity.getConfiguration() != null) complexityFactor += entity.getConfiguration().size();
        
        String maintainabilityGrade = "A";
        if (complexityFactor > 20) maintainabilityGrade = "F";
        else if (complexityFactor > 15) maintainabilityGrade = "D";
        else if (complexityFactor > 10) maintainabilityGrade = "C";
        else if (complexityFactor > 5) maintainabilityGrade = "B";

        return new DeveloperMetricsDTO(testabilityScore, maintainabilityGrade, evaluationCount);
    }

    /**
     * Rolls back an agent to the configuration stored in the given audit snapshot.
     * Creates a new audit record with action "ROLLBACK" and incremented version number.
     */
    @Override
    @Transactional
    public com.operativus.agentmanager.core.model.definitions.AgentDefinition rollbackAgent(String agentId, String auditId) {
        AgentAuditEntity snapshot = auditRepository.findById(auditId)
                .orElseThrow(() -> new ResourceNotFoundException("AgentAudit", auditId));

        if (!snapshot.getAgentId().equals(agentId)) {
            throw new BusinessValidationException("Audit record does not belong to agent: " + agentId);
        }
        if (snapshot.getChangeset() == null || snapshot.getChangeset().isBlank()) {
            throw new BusinessValidationException("Audit record has no stored configuration to rollback to.");
        }

        try {
            com.operativus.agentmanager.core.model.definitions.AgentDefinition restoredDef =
                    objectMapper.readValue(snapshot.getChangeset(), com.operativus.agentmanager.core.model.definitions.AgentDefinition.class);
            com.operativus.agentmanager.core.model.definitions.AgentDefinition result = updateAgent(agentId, restoredDef);
            // Reload the entity post-update to obtain orgId for the audit row (Fix C).
            // Rollback is a rare admin path; one extra repo call is acceptable.
            AgentEntity rollbackTarget = agentRepository.findByIdAndOrgId(agentId, callerOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("Agent", agentId));
            logAudit(rollbackTarget, AuditActionType.ROLLBACK, restoredDef);
            log.info("Agent {} rolled back to audit version {}", agentId, snapshot.getVersionNumber());
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessValidationException("Failed to deserialize stored agent configuration: " + e.getMessage());
        }
    }

    private void logAudit(AgentEntity agent, AuditActionType action, Object changesetObj) {
        try {
            String username = SecurityPrincipals.SYSTEM_PRINCIPAL;
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !SecurityPrincipals.ANONYMOUS_USER.equals(auth.getPrincipal())) {
                username = auth.getName();
            }
            String changesetJson = changesetObj != null ? objectMapper.writeValueAsString(changesetObj) : "{}";
            AgentAuditEntity audit = new AgentAuditEntity(agent.getId(), agent.getOrgId(), action.getValue(), username, changesetJson);
            auditRepository.save(audit);
            log.trace("Audit log saved. Agent ID: {}, Action: {}", agent.getId(), action);
        } catch (Exception e) {
            // If audit stringification fails, log it but don't crash the main transaction
            log.error("Failed to serialize audit changeset for agent {} action {}: {}", agent.getId(), action, e.getMessage(), e);
        }
    }

    private AgentDefinition mapToDefinition(AgentEntity entity) {
        return new AgentDefinition(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getInstructions(),
                entity.getModelId(),
                entity.getContextWindowSize(),
                entity.getMemoryEnabled(),
                entity.getAddHistoryToMessages(),
                entity.getTools(),
                Boolean.TRUE.equals(entity.isReasoningEnabled()),
                Boolean.TRUE.equals(entity.isTeam()),
                entity.getTeamMode(),
                entity.getMembers(),
                entity.getAllowedRoles(),
                Boolean.TRUE.equals(entity.isRequiresPiiRedaction()),
                Boolean.TRUE.equals(entity.isApprovedForProduction()),
                Boolean.TRUE.equals(entity.isMaintenanceMode()),
                Boolean.TRUE.equals(entity.isActive()),
                entity.getConfiguration(),
                entity.getMarkdownDocs(),
                entity.getSupportChannel(),
                entity.getPrimaryOwner(),
                entity.getSupportedLocales(),
                entity.getAccessibilityCompatibility(),
                entity.getTrainingDatasets(),
                entity.getKnowledgeBaseIds(),
                Boolean.TRUE.equals(entity.isEnforceJsonOutput()),
                entity.getTemperature(),
                entity.getTopP(),
                entity.getFrequencyPenalty(),
                entity.getSystemPromptMode(),
                entity.getMaxConcurrentExecutions(),
                entity.getFinOpsTokenBudget(),
                entity.getFinOpsRiskTier(),
                entity.getSecurityTier(),
                entity.getComplianceTier(),
                entity.getCompressionThreshold(),
                entity.getSummarizationThreshold(),
                entity.getOptimizationModelId(),
                entity.getPreHooks(),
                entity.getPostHooks(),
                false, // §9 MEM-2: AgentEntity has no per-row isolateMemory; only teams.
                entity.getFallbackModelIds(), // gap #8 — per-agent fallback chain.
                entity.getHumanReview(), // REQ-HR follow-up — surfaces the agent's own human_review JSONB.
                entity.getCapabilities() == null ? null : java.util.Arrays.asList(entity.getCapabilities())
        );
    }

    private AgentEntity mapFromDefinition(AgentDefinition definition) {
        AgentEntity entity = new AgentEntity(
                definition.id(),
                definition.name(),
                definition.description(),
                definition.instructions(),
                definition.modelId(),
                definition.contextWindowSize(),
                definition.memoryEnabled(),
                definition.addHistoryToMessages(),
                definition.tools() != null ? new java.util.ArrayList<>(definition.tools()) : new java.util.ArrayList<>(),
                definition.monitoringEnabled(),
                definition.isTeam(),
                definition.teamMode(),
                definition.members(),
                definition.allowedRoles(),
                definition.markdownDocs(),
                definition.supportChannel(),
                definition.primaryOwner(),
                definition.supportedLocales(),
                definition.accessibilityCompatibility(),
                definition.trainingDatasets(),
                definition.requiresPiiRedaction(),
                definition.approvedForProduction(),
                definition.maintenanceMode(),
                definition.active(),
                definition.enforceJsonOutput(),
                definition.configuration(),
                definition.compressionThreshold(),
                definition.summarizationThreshold(),
                definition.optimizationModelId()
        );
        entity.setKnowledgeBaseIds(definition.knowledgeBaseIds());
        entity.setSecurityTier(definition.securityTier() != null ? definition.securityTier() : 1);
        entity.setComplianceTier(definition.complianceTier() != null ? definition.complianceTier() : com.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD);
        entity.setTemperature(definition.temperature());
        entity.setTopP(definition.topP());
        entity.setFrequencyPenalty(definition.frequencyPenalty());
        entity.setSystemPromptMode(definition.systemPromptMode());
        entity.setMaxConcurrentExecutions(definition.maxConcurrentExecutions());
        entity.setFinOpsTokenBudget(definition.finOpsTokenBudget());
        entity.setFinOpsRiskTier(definition.finOpsRiskTier());
        entity.setPreHooks(definition.preHooks() != null ? new java.util.ArrayList<>(definition.preHooks()) : new java.util.ArrayList<>());
        entity.setPostHooks(definition.postHooks() != null ? new java.util.ArrayList<>(definition.postHooks()) : new java.util.ArrayList<>());
        entity.setHumanReview(definition.humanReview());
        entity.setFallbackModelIds(definition.fallbackModelIds() != null
                ? new java.util.ArrayList<>(definition.fallbackModelIds())
                : null);
        entity.setCapabilities(definition.capabilities() == null
                ? new String[0]
                : definition.capabilities().toArray(new String[0]));
        return entity;
    }

    @Transactional
    public void cancelRun(String runId) {
        // Type-narrow to RunOperations because RunRepository inherits findById(String)
        // from BOTH JpaRepository (CrudRepository) and RunOperations — calling on the
        // RunRepository field directly is ambiguous to javac.
        com.operativus.agentmanager.core.registry.RunOperations runOps = runRepository;
        AgentRun run = runOps.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("AgentRun", runId));
        // Cross-tenant guard. callerOrgId() returns the caller's bound orgId (or
        // DEFAULT_SYSTEM_ORG fallback). Throw 404-shape rather than 403 to avoid
        // leaking which runIds exist in foreign orgs — matches the existence-leak-
        // protection pattern used by ComplianceController.requireSameOrgOrSelf and
        // every other tenant-scoped method on this service.
        // Pre-fix (PR #969 only added the controller class gate): an admin from
        // org A could cancel a run from org B by knowing the runId.
        // Legacy null-org rows fail equality on .equals(null) → 404; that's the
        // safe default and prevents anonymous-org cleanup paths from bypassing.
        if (!callerOrgId().equals(run.getOrgId())) {
            throw new ResourceNotFoundException("AgentRun", runId);
        }
        if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED || run.getStatus() == RunStatus.CANCELLED) {
            // Admin-API contract: surface a 4xx for already-terminal rows so the caller
            // can distinguish "cancelled by you" from "already in a terminal state".
            // The user-API path (RunExecutionManager.cancel directly) silently no-ops
            // in this case and returns 204; that's intentional for client-driven cancels
            // where the user may not know the latest row state.
            throw new BusinessValidationException("Cannot cancel run in terminal state: " + run.getStatus());
        }
        // Delegate the actual cancellation to AgentOperations (which routes through
        // RunExecutionManager.cancel → AgentRunFinalizer). Admin cancel must not be
        // a separate code path that races the contract owner's @Version-checked write.
        agentOperations.cancelRun(runId);
        log.info("Run {} cancelled by admin.", runId);
    }
}
