package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.compute.routing.ClassifierDecision;
import com.operativus.agentmanager.compute.routing.LlmAgentClassifier;
import com.operativus.agentmanager.compute.routing.SemanticAgentScorer;
import com.operativus.agentmanager.control.dto.OrgRoutingConfigRequest;
import com.operativus.agentmanager.control.dto.OrgRoutingConfigResponse;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.OrgRoutingConfigRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.OrgRoutingConfig;
import com.operativus.agentmanager.core.entity.RoutingDecisionEntity;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.RoutingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain Responsibility: Manages per-org routing config + implements {@link RoutingResolver}
 *     so the compute layer can resolve a target {@code agentId} for the universal-dispatch
 *     entry point ({@code POST /api/runs}). CRUD methods are tenant-scoped via
 *     {@link AgentContextHolder#getOrgId()}. The {@link #resolveAgentId} contract takes
 *     {@code orgId} explicitly because it is invoked from inside the request thread
 *     before the tenant context has been bound to the run.
 *
 *     <p>Three resolution strategies compose in priority order:
 *     <ol>
 *       <li>{@code default_router_agent_id} — designated team; short-circuits resolution</li>
 *       <li>{@code llm_classifier_enabled} — LLM classifier over active agents (stub in
 *           this PR; real implementation in a follow-up that wires the classifier
 *           ChatClient through {@code AgentClientFactory})</li>
 *       <li>{@code rule_classifier_enabled} — case-insensitive substring match of message
 *           against agent {@code description}; returns the first match by id-sort for
 *           determinism</li>
 *     </ol>
 *     If all strategies miss, {@code fallback_agent_id} is returned (may be null).
 * State: Stateless
 */
@Service
public class RoutingResolverService implements RoutingResolver {

    private static final Logger log = LoggerFactory.getLogger(RoutingResolverService.class);

    static final String ROUTER_STRATEGY = "ROUTER";

    private final OrgRoutingConfigRepository configRepository;
    private final AgentRepository agentRepository;
    private final AgentRegistry agentRegistry;
    private final RoutingDecisionRecorderService recorder;
    private final LlmAgentClassifier llmClassifier;
    private final SemanticAgentScorer semanticScorer;

    public RoutingResolverService(OrgRoutingConfigRepository configRepository,
                                  AgentRepository agentRepository,
                                  AgentRegistry agentRegistry,
                                  RoutingDecisionRecorderService recorder,
                                  LlmAgentClassifier llmClassifier,
                                  SemanticAgentScorer semanticScorer) {
        this.configRepository = configRepository;
        this.agentRepository = agentRepository;
        this.agentRegistry = agentRegistry;
        this.recorder = recorder;
        this.llmClassifier = llmClassifier;
        this.semanticScorer = semanticScorer;
    }

    // --- RoutingResolver SPI ---

    @Override
    public String resolveAgentId(String orgId, String userId, String message) {
        long startMs = System.currentTimeMillis();
        String resolved = null;
        RoutingDecisionEntity.StrategyUsed strategyUsed = RoutingDecisionEntity.StrategyUsed.NONE;
        int candidateCount = 0;
        String rationale = null;
        try {
            if (orgId == null || orgId.isBlank()) {
                return null;
            }
            Optional<OrgRoutingConfig> cfgOpt = configRepository.findByOrgId(orgId);
            if (cfgOpt.isEmpty()) {
                rationale = "no org_routing_config row";
                return null;
            }
            OrgRoutingConfig cfg = cfgOpt.get();

            // Strategy 1: default_router team — short-circuit if configured AND still a
            // ROUTER-strategy team. The cached strategy on the config row is advisory; the
            // runtime AgentRegistry lookup is the source of truth. If the configured agent
            // has been demoted or deleted since upsert, fall through to strategy 2.
            String defaultRouter = cfg.getDefaultRouterAgentId();
            if (defaultRouter != null && !defaultRouter.isBlank()
                    && agentRepository.existsByIdAndOrgId(defaultRouter, orgId)) {
                AgentDefinition routerDef = agentRegistry.findById(defaultRouter, orgId);
                if (routerDef != null && routerDef.isTeam() && ROUTER_STRATEGY.equalsIgnoreCase(routerDef.teamMode())) {
                    strategyUsed = RoutingDecisionEntity.StrategyUsed.DEFAULT_ROUTER;
                    resolved = defaultRouter;
                    return resolved;
                }
                log.warn("default_router_agent_id {} for org {} is no longer a ROUTER team — falling through to strategy 2",
                        defaultRouter, orgId);
                rationale = "default_router demoted from ROUTER team";
            }

            // Strategy 2: LLM classifier — calls the org-configured (or system-default)
            // classifier model to pick the best-fit agent from the org's active roster.
            // Soft-fails on any error (model missing, ChatClient throws, confidence below
            // floor, returned agentId not in candidate set) so the cascade continues.
            if (Boolean.TRUE.equals(cfg.getLlmClassifierEnabled()) && message != null && !message.isBlank()) {
                List<AgentDefinition> candidates = agentRegistry.findAll(false, orgId);
                if (!candidates.isEmpty()) {
                    candidateCount = candidates.size();
                    Optional<ClassifierDecision> decision = llmClassifier.classify(
                            orgId, userId, message, candidates, cfg.getClassifierModelId());
                    if (decision.isPresent()) {
                        strategyUsed = RoutingDecisionEntity.StrategyUsed.LLM_CLASSIFIER;
                        resolved = decision.get().agentId();
                        rationale = decision.get().rationale();
                        return resolved;
                    }
                }
            }

            // Strategy 3: rule-based classifier — semantic (pgvector cosine) when the org
            // has opted in via semantic_scoring_enabled, falling back to the legacy
            // case-insensitive substring matcher otherwise. The semantic path also soft-
            // fails to the substring matcher if it returns empty, so an org that flips the
            // toggle on without backfilling embeddings still gets routed by substring.
            if (Boolean.TRUE.equals(cfg.getRuleClassifierEnabled()) && message != null && !message.isBlank()) {
                if (Boolean.TRUE.equals(cfg.getSemanticScoringEnabled())) {
                    List<AgentDefinition> activeRoster = agentRegistry.findAll(false, orgId);
                    Optional<String> semanticMatch = semanticScorer.scoreAndSelectBest(orgId, message, activeRoster);
                    if (semanticMatch.isPresent()) {
                        strategyUsed = RoutingDecisionEntity.StrategyUsed.SEMANTIC_SCORING;
                        resolved = semanticMatch.get();
                        return resolved;
                    }
                }
                String needle = message.toLowerCase();
                List<AgentEntity> candidates = agentRepository
                        .findAllByOrgIdAndActive(orgId, true, Pageable.unpaged())
                        .getContent();
                candidateCount = candidates.size();
                String match = candidates.stream()
                        .filter(a -> a.getDescription() != null && !a.getDescription().isBlank())
                        .filter(a -> needle.contains(a.getDescription().toLowerCase())
                                || a.getDescription().toLowerCase().contains(extractKeyword(needle)))
                        .map(AgentEntity::getId)
                        .sorted()
                        .findFirst()
                        .orElse(null);
                if (match != null) {
                    strategyUsed = RoutingDecisionEntity.StrategyUsed.RULE_SUBSTRING;
                    resolved = match;
                    return resolved;
                }
            }

            // Fallback
            String fallback = cfg.getFallbackAgentId();
            if (fallback != null && !fallback.isBlank()
                    && agentRepository.existsByIdAndOrgId(fallback, orgId)) {
                strategyUsed = RoutingDecisionEntity.StrategyUsed.FALLBACK;
                resolved = fallback;
                return resolved;
            }
            return null;
        } finally {
            long latency = System.currentTimeMillis() - startMs;
            // Only record when we have an org scope to attribute the decision to.
            if (orgId != null && !orgId.isBlank()) {
                recorder.recordDecision(orgId, userId, null, message, resolved,
                        strategyUsed, null, candidateCount, latency, rationale);
            }
        }
    }

    // --- CRUD ---

    public OrgRoutingConfigResponse getConfig() {
        return configRepository.findByOrgId(callerOrgId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("OrgRoutingConfig", callerOrgId()));
    }

    @Transactional
    public OrgRoutingConfigResponse upsertConfig(OrgRoutingConfigRequest request) {
        String orgId = callerOrgId();
        validateAgentOwnership(request.defaultRouterAgentId(), orgId, "defaultRouterAgentId");
        validateAgentOwnership(request.fallbackAgentId(), orgId, "fallbackAgentId");
        String cachedStrategy = validateDefaultRouterIsRouterTeam(request.defaultRouterAgentId(), orgId);

        OrgRoutingConfig cfg = configRepository.findByOrgId(orgId)
                .orElseGet(() -> new OrgRoutingConfig(UUID.randomUUID().toString(), orgId));

        if (request.defaultRouterAgentId() != null) {
            String resolved = blankToNull(request.defaultRouterAgentId());
            cfg.setDefaultRouterAgentId(resolved);
            cfg.setDefaultRouterCachedStrategy(resolved == null ? null : cachedStrategy);
        }
        if (request.fallbackAgentId() != null) cfg.setFallbackAgentId(blankToNull(request.fallbackAgentId()));
        if (request.llmClassifierEnabled() != null) cfg.setLlmClassifierEnabled(request.llmClassifierEnabled());
        if (request.ruleClassifierEnabled() != null) cfg.setRuleClassifierEnabled(request.ruleClassifierEnabled());
        if (request.classifierModelId() != null) cfg.setClassifierModelId(blankToNull(request.classifierModelId()));
        if (request.semanticScoringEnabled() != null) cfg.setSemanticScoringEnabled(request.semanticScoringEnabled());

        return toResponse(configRepository.save(cfg));
    }

    @Transactional
    public void deleteConfig() {
        String orgId = callerOrgId();
        configRepository.findByOrgId(orgId).ifPresentOrElse(
                cfg -> configRepository.deleteById(cfg.getId()),
                () -> { throw new ResourceNotFoundException("OrgRoutingConfig", orgId); });
    }

    // --- Internal helpers ---

    private void validateAgentOwnership(String agentId, String orgId, String fieldName) {
        if (agentId == null || agentId.isBlank()) return;
        if (!agentRepository.existsByIdAndOrgId(agentId, orgId)) {
            // Cross-tenant agent reference — 404 (not 403) per §79 RBAC pattern.
            throw new ResourceNotFoundException("Agent referenced by " + fieldName, agentId);
        }
    }

    /**
     * Validates that {@code defaultRouterAgentId} (when non-blank) references a team
     * whose {@code teamMode == "ROUTER"}. Returns the resolved teamMode string for the
     * config row's advisory cache, or {@code null} when no router is configured.
     * Throws {@link BusinessValidationException} when the referenced agent exists but
     * is not a ROUTER team — the cascade depends on Strategy 1 actually running
     * RouterOrchestrator.execute, which only fires for ROUTER-strategy teams.
     */
    private String validateDefaultRouterIsRouterTeam(String defaultRouterAgentId, String orgId) {
        if (defaultRouterAgentId == null || defaultRouterAgentId.isBlank()) return null;
        AgentDefinition def = agentRegistry.findById(defaultRouterAgentId, orgId);
        if (def == null) {
            // Ownership check above already covers this; defensive belt-and-braces.
            throw new ResourceNotFoundException("Agent referenced by defaultRouterAgentId", defaultRouterAgentId);
        }
        if (!def.isTeam() || !ROUTER_STRATEGY.equalsIgnoreCase(def.teamMode())) {
            throw new BusinessValidationException(
                    "defaultRouterAgentId must reference a ROUTER-strategy team agent (received teamMode='"
                            + def.teamMode() + "', isTeam=" + def.isTeam() + ")");
        }
        return def.teamMode();
    }

    private static String callerOrgId() {
        String orgId = AgentContextHolder.getOrgId();
        return (orgId != null && !orgId.isBlank()) ? orgId : TenantConstants.DEFAULT_SYSTEM_ORG;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Lightweight keyword extraction for the rule classifier: take the longest space-separated
     * token from the lowercased message. Substring-style matching against agent descriptions
     * is intentionally simple — a proper retrieval-style classifier lives behind
     * {@code llm_classifier_enabled} in a follow-up PR.
     */
    private static String extractKeyword(String lowercaseMessage) {
        String[] parts = lowercaseMessage.split("\\s+");
        String longest = "";
        for (String part : parts) {
            if (part.length() > longest.length()) longest = part;
        }
        return longest;
    }

    private OrgRoutingConfigResponse toResponse(OrgRoutingConfig c) {
        return new OrgRoutingConfigResponse(
                c.getId(), c.getOrgId(), c.getDefaultRouterAgentId(), c.getFallbackAgentId(),
                c.getLlmClassifierEnabled(), c.getRuleClassifierEnabled(),
                c.getClassifierModelId(),
                c.getSemanticScoringEnabled(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
