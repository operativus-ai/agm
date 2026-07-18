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
import com.operativus.agentmanager.core.entity.ComplianceTier;
import com.operativus.agentmanager.core.entity.OrgRoutingConfig;
import com.operativus.agentmanager.core.entity.RoutingDecisionEntity;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.ResourceNotFoundException;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RoutingResolverServiceTest {

    private static final String ORG = "TEST_ORG";

    @Mock private OrgRoutingConfigRepository configRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentRegistry agentRegistry;
    @Mock private RoutingDecisionRecorderService recorder;
    @Mock private LlmAgentClassifier llmClassifier;
    @Mock private SemanticAgentScorer semanticScorer;

    private RoutingResolverService service;
    private MockedStatic<AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        mockedContext = mockStatic(AgentContextHolder.class);
        mockedContext.when(AgentContextHolder::getOrgId).thenReturn(ORG);
        service = new RoutingResolverService(configRepository, agentRepository, agentRegistry,
                recorder, llmClassifier, semanticScorer);
    }

    @AfterEach
    void tearDown() {
        if (mockedContext != null) mockedContext.close();
    }

    private OrgRoutingConfig newConfig() {
        return new OrgRoutingConfig("cfg-1", ORG);
    }

    private AgentEntity agent(String id, String description) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(ORG);
        a.setDescription(description);
        a.setActive(true);
        return a;
    }

    private static AgentDefinition teamDef(String id, String teamMode) {
        return new AgentDefinition(
                id, id, "desc", "instructions", "model-x",
                null, null, null, null,
                false, true, teamMode,
                List.of("m-a", "m-b"),
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                null, null, null, null);
    }

    private static AgentDefinition singleAgentDef(String id) {
        return new AgentDefinition(
                id, id, "desc", "instructions", "model-x",
                null, null, null, null,
                false, false, null,
                null,
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                null, null, null, null);
    }

    // --- resolveAgentId — strategy 1: default_router ---

    @Test
    void resolveAgentId_defaultRouterConfigured_returnsRouter() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setDefaultRouterAgentId("router-team");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRepository.existsByIdAndOrgId("router-team", ORG)).thenReturn(true);
        when(agentRegistry.findById("router-team", ORG)).thenReturn(teamDef("router-team", "ROUTER"));

        assertEquals("router-team", service.resolveAgentId(ORG, "user-1", "Anything"));
    }

    @Test
    void resolveAgentId_defaultRouterNoLongerRouterTeam_skipsAndContinues() {
        // DR-FR-1: if the cached default_router was a ROUTER team at upsert but is now a
        // SEQUENTIAL team (or a non-team), the resolver must NOT short-circuit on it.
        // It logs a warning and falls through to the next strategy / fallback.
        OrgRoutingConfig cfg = newConfig();
        cfg.setDefaultRouterAgentId("demoted-team");
        cfg.setFallbackAgentId("fallback-agent");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRepository.existsByIdAndOrgId("demoted-team", ORG)).thenReturn(true);
        when(agentRegistry.findById("demoted-team", ORG)).thenReturn(teamDef("demoted-team", "SEQUENTIAL"));
        when(agentRepository.existsByIdAndOrgId("fallback-agent", ORG)).thenReturn(true);

        assertEquals("fallback-agent", service.resolveAgentId(ORG, "user-1", "anything"));
    }

    @Test
    void resolveAgentId_defaultRouterCrossTenantStale_skipsAndContinues() {
        // Defensive: if a stale default_router_agent_id points to an agent that no longer
        // belongs to the org (e.g., agent was moved or deleted), the resolver must not
        // short-circuit on it. Falls through to other strategies / fallback.
        OrgRoutingConfig cfg = newConfig();
        cfg.setDefaultRouterAgentId("ghost-router");
        cfg.setFallbackAgentId("fallback-agent");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRepository.existsByIdAndOrgId("ghost-router", ORG)).thenReturn(false);
        when(agentRepository.existsByIdAndOrgId("fallback-agent", ORG)).thenReturn(true);

        assertEquals("fallback-agent", service.resolveAgentId(ORG, "user-1", "hi"));
    }

    // --- resolveAgentId — strategy 3: rule classifier ---

    @Test
    void resolveAgentId_ruleClassifier_messageContainsAgentDescription_returnsAgent() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setRuleClassifierEnabled(true);
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));

        AgentEntity weather = agent("weather-agent", "Handles weather questions and forecasts");
        AgentEntity billing = agent("billing-agent", "Handles invoices and payment issues");
        when(agentRepository.findAllByOrgIdAndActive(eq(ORG), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(weather, billing)));

        // Message contains the agent description as a substring
        String agentId = service.resolveAgentId(ORG, "user-1",
                "I need help — Handles weather questions and forecasts is what I want");
        assertEquals("weather-agent", agentId);
    }

    @Test
    void resolveAgentId_ruleClassifier_descriptionContainsMessageKeyword_returnsAgent() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setRuleClassifierEnabled(true);
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));

        AgentEntity weather = agent("weather-agent", "weather forecasts and alerts");
        when(agentRepository.findAllByOrgIdAndActive(eq(ORG), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(weather)));

        // Longest keyword "weather" is contained in the description
        String agentId = service.resolveAgentId(ORG, "user-1", "give me weather updates");
        assertEquals("weather-agent", agentId);
    }

    @Test
    void resolveAgentId_ruleClassifier_multipleMatches_picksByIdSortDeterministically() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setRuleClassifierEnabled(true);
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));

        // Both agents match "weather"; id sort picks "alpha-weather" before "zeta-weather"
        AgentEntity zeta = agent("zeta-weather", "weather agent");
        AgentEntity alpha = agent("alpha-weather", "weather agent");
        when(agentRepository.findAllByOrgIdAndActive(eq(ORG), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(zeta, alpha)));

        assertEquals("alpha-weather", service.resolveAgentId(ORG, "user-1", "weather please"));
    }

    @Test
    void resolveAgentId_ruleClassifier_blankMessage_skips() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setRuleClassifierEnabled(true);
        cfg.setFallbackAgentId("fallback");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRepository.existsByIdAndOrgId("fallback", ORG)).thenReturn(true);

        assertEquals("fallback", service.resolveAgentId(ORG, "user-1", "  "));
        // Verify the rule classifier did NOT query for candidate agents on a blank message
        verify(agentRepository, never()).findAllByOrgIdAndActive(any(), anyBoolean(), any());
    }

    // --- resolveAgentId — fallback ---

    @Test
    void resolveAgentId_allStrategiesMiss_returnsFallback() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setRuleClassifierEnabled(true);
        cfg.setFallbackAgentId("fallback-agent");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRepository.findAllByOrgIdAndActive(eq(ORG), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(agentRepository.existsByIdAndOrgId("fallback-agent", ORG)).thenReturn(true);

        assertEquals("fallback-agent", service.resolveAgentId(ORG, "user-1", "anything"));
    }

    @Test
    void resolveAgentId_noConfigForOrg_returnsNull() {
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.empty());

        assertNull(service.resolveAgentId(ORG, "user-1", "anything"));
    }

    @Test
    void resolveAgentId_allStrategiesDisabledNoFallback_returnsNull() {
        OrgRoutingConfig cfg = newConfig();
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));

        assertNull(service.resolveAgentId(ORG, "user-1", "anything"));
    }

    @Test
    void resolveAgentId_nullOrBlankOrgId_returnsNull() {
        assertNull(service.resolveAgentId(null, "user-1", "msg"));
        assertNull(service.resolveAgentId("  ", "user-1", "msg"));
        verifyNoInteractions(configRepository);
    }

    @Test
    void resolveAgentId_llmClassifierReturnsValidDecision_returnsClassifiedAgent() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setLlmClassifierEnabled(true);
        cfg.setClassifierModelId("classifier-model");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        List<AgentDefinition> roster = List.of(singleAgentDef("agent-x"), singleAgentDef("agent-y"));
        when(agentRegistry.findAll(eq(false), eq(ORG))).thenReturn(roster);
        when(llmClassifier.classify(eq(ORG), eq("user-1"), eq("route me"), eq(roster), eq("classifier-model")))
                .thenReturn(Optional.of(new ClassifierDecision("agent-y", 0.92, "matched on topic")));

        assertEquals("agent-y", service.resolveAgentId(ORG, "user-1", "route me"));
    }

    @Test
    void resolveAgentId_llmClassifierReturnsEmpty_fallsThroughToFallback() {
        // Classifier soft-fails (model missing, confidence too low, etc.). Cascade
        // continues to strategy 3 (disabled here) and on to fallback.
        OrgRoutingConfig cfg = newConfig();
        cfg.setLlmClassifierEnabled(true);
        cfg.setFallbackAgentId("fallback-agent");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        List<AgentDefinition> roster = List.of(singleAgentDef("agent-x"));
        when(agentRegistry.findAll(eq(false), eq(ORG))).thenReturn(roster);
        when(llmClassifier.classify(eq(ORG), eq("user-1"), eq("anything"), eq(roster), eq(null)))
                .thenReturn(Optional.empty());
        when(agentRepository.existsByIdAndOrgId("fallback-agent", ORG)).thenReturn(true);

        assertEquals("fallback-agent", service.resolveAgentId(ORG, "user-1", "anything"));
    }

    @Test
    void resolveAgentId_semanticScoringEnabled_returnsSemanticMatch() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setRuleClassifierEnabled(true);
        cfg.setSemanticScoringEnabled(true);
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        List<AgentDefinition> roster = List.of(singleAgentDef("agent-a"), singleAgentDef("agent-b"));
        when(agentRegistry.findAll(eq(false), eq(ORG))).thenReturn(roster);
        when(semanticScorer.scoreAndSelectBest(eq(ORG), eq("anything"), eq(roster)))
                .thenReturn(Optional.of("agent-b"));

        assertEquals("agent-b", service.resolveAgentId(ORG, "user-1", "anything"));
        // Semantic short-circuits — substring path must not query the AgentRepository.
        verify(agentRepository, never()).findAllByOrgIdAndActive(any(), anyBoolean(), any());
    }

    @Test
    void resolveAgentId_semanticScoringEmpty_fallsBackToSubstring() {
        // Org has opted into semantic scoring but the embedding pipeline is cold or the
        // similarity threshold isn't met. The substring matcher still runs.
        OrgRoutingConfig cfg = newConfig();
        cfg.setRuleClassifierEnabled(true);
        cfg.setSemanticScoringEnabled(true);
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRegistry.findAll(eq(false), eq(ORG))).thenReturn(List.of(singleAgentDef("any")));
        when(semanticScorer.scoreAndSelectBest(eq(ORG), any(), any())).thenReturn(Optional.empty());

        AgentEntity weather = agent("weather-agent", "Handles weather questions and forecasts");
        when(agentRepository.findAllByOrgIdAndActive(eq(ORG), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(weather)));

        String agentId = service.resolveAgentId(ORG, "user-1", "weather please");
        assertEquals("weather-agent", agentId);
    }

    @Test
    void resolveAgentId_llmClassifierEnabledButEmptyRoster_doesNotInvokeClassifier() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setLlmClassifierEnabled(true);
        cfg.setFallbackAgentId("fallback-agent");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRegistry.findAll(eq(false), eq(ORG))).thenReturn(List.of());
        when(agentRepository.existsByIdAndOrgId("fallback-agent", ORG)).thenReturn(true);

        assertEquals("fallback-agent", service.resolveAgentId(ORG, "user-1", "anything"));
        verifyNoInteractions(llmClassifier);
    }

    // --- CRUD: getConfig ---

    @Test
    void getConfig_exists_returnsResponse() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setDefaultRouterAgentId("r");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));

        OrgRoutingConfigResponse resp = service.getConfig();
        assertEquals(ORG, resp.orgId());
        assertEquals("r", resp.defaultRouterAgentId());
    }

    @Test
    void getConfig_missing_throwsResourceNotFound() {
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getConfig());
    }

    // --- CRUD: upsertConfig ---

    @Test
    void upsertConfig_newRow_createsWithGeneratedIdAndOrgStamp() {
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest(
                "router-1", "fallback-1", true, true, null, null);
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(agentRepository.existsByIdAndOrgId("router-1", ORG)).thenReturn(true);
        when(agentRepository.existsByIdAndOrgId("fallback-1", ORG)).thenReturn(true);
        when(agentRegistry.findById("router-1", ORG)).thenReturn(teamDef("router-1", "ROUTER"));
        when(configRepository.save(any(OrgRoutingConfig.class))).thenAnswer(i -> i.getArgument(0));

        OrgRoutingConfigResponse resp = service.upsertConfig(req);

        ArgumentCaptor<OrgRoutingConfig> captor = ArgumentCaptor.forClass(OrgRoutingConfig.class);
        verify(configRepository).save(captor.capture());
        assertEquals(ORG, captor.getValue().getOrgId());
        assertNotNull(captor.getValue().getId());
        assertEquals("router-1", captor.getValue().getDefaultRouterAgentId());
        assertEquals("ROUTER", captor.getValue().getDefaultRouterCachedStrategy());
        assertEquals("fallback-1", captor.getValue().getFallbackAgentId());
        assertTrue(captor.getValue().getLlmClassifierEnabled());
        assertTrue(captor.getValue().getRuleClassifierEnabled());

        assertEquals(ORG, resp.orgId());
    }

    @Test
    void upsertConfig_existingRow_updatesInPlace() {
        OrgRoutingConfig existing = newConfig();
        existing.setDefaultRouterAgentId("old-router");
        existing.setLlmClassifierEnabled(false);
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest(
                "new-router", null, true, null, null, null);

        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByIdAndOrgId("new-router", ORG)).thenReturn(true);
        when(agentRegistry.findById("new-router", ORG)).thenReturn(teamDef("new-router", "ROUTER"));
        when(configRepository.save(any(OrgRoutingConfig.class))).thenAnswer(i -> i.getArgument(0));

        service.upsertConfig(req);

        ArgumentCaptor<OrgRoutingConfig> captor = ArgumentCaptor.forClass(OrgRoutingConfig.class);
        verify(configRepository).save(captor.capture());
        assertEquals("new-router", captor.getValue().getDefaultRouterAgentId());
        assertEquals("ROUTER", captor.getValue().getDefaultRouterCachedStrategy());
        assertTrue(captor.getValue().getLlmClassifierEnabled(),
                "non-null field in request must update");
    }

    @Test
    void upsertConfig_defaultRouterIsNotRouterTeam_throwsBusinessValidation() {
        // DR-FR-1: SEQUENTIAL team configured as default_router must be rejected at upsert.
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest(
                "sequential-team", null, null, null, null, null);
        when(agentRepository.existsByIdAndOrgId("sequential-team", ORG)).thenReturn(true);
        when(agentRegistry.findById("sequential-team", ORG))
                .thenReturn(teamDef("sequential-team", "SEQUENTIAL"));

        assertThrows(BusinessValidationException.class, () -> service.upsertConfig(req));
        verify(configRepository, never()).save(any());
    }

    @Test
    void upsertConfig_defaultRouterIsNonTeamAgent_throwsBusinessValidation() {
        // DR-FR-1: a single-agent (isTeam=false) configured as default_router must be rejected.
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest(
                "single-agent", null, null, null, null, null);
        when(agentRepository.existsByIdAndOrgId("single-agent", ORG)).thenReturn(true);
        when(agentRegistry.findById("single-agent", ORG)).thenReturn(singleAgentDef("single-agent"));

        assertThrows(BusinessValidationException.class, () -> service.upsertConfig(req));
        verify(configRepository, never()).save(any());
    }

    @Test
    void upsertConfig_crossTenantDefaultRouter_throwsResourceNotFound() {
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest(
                "router-other-org", null, null, null, null, null);
        when(agentRepository.existsByIdAndOrgId("router-other-org", ORG)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.upsertConfig(req));
        verify(configRepository, never()).save(any());
    }

    @Test
    void upsertConfig_crossTenantFallback_throwsResourceNotFound() {
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest(
                null, "fallback-other-org", null, null, null, null);
        when(agentRepository.existsByIdAndOrgId("fallback-other-org", ORG)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.upsertConfig(req));
        verify(configRepository, never()).save(any());
    }

    @Test
    void upsertConfig_blankAgentId_treatedAsNull() {
        OrgRoutingConfigRequest req = new OrgRoutingConfigRequest("   ", "  ", null, null, null, null);
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(configRepository.save(any(OrgRoutingConfig.class))).thenAnswer(i -> i.getArgument(0));

        service.upsertConfig(req);

        // Blank agent ids bypass ownership validation (no FK lookup at all)
        verify(agentRepository, never()).existsByIdAndOrgId(any(), any());
    }

    // --- CRUD: deleteConfig ---

    @Test
    void deleteConfig_exists_deletes() {
        OrgRoutingConfig cfg = newConfig();
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));

        service.deleteConfig();
        verify(configRepository).deleteById("cfg-1");
    }

    @Test
    void deleteConfig_missing_throwsResourceNotFound() {
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.deleteConfig());
        verify(configRepository, never()).deleteById(any());
    }

    // --- DR-FR-4 recorder integration ---

    @Test
    void resolveAgentId_records_resolvedDecision_viaRecorder() {
        OrgRoutingConfig cfg = newConfig();
        cfg.setDefaultRouterAgentId("router-team");
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));
        when(agentRepository.existsByIdAndOrgId("router-team", ORG)).thenReturn(true);
        when(agentRegistry.findById("router-team", ORG)).thenReturn(teamDef("router-team", "ROUTER"));

        service.resolveAgentId(ORG, "user-1", "anything");

        verify(recorder).recordDecision(
                eq(ORG), eq("user-1"), eq(null), eq("anything"),
                eq("router-team"),
                eq(RoutingDecisionEntity.StrategyUsed.DEFAULT_ROUTER),
                eq(null), eq(0), anyLong(), eq(null));
    }

    @Test
    void resolveAgentId_records_unresolvedDecision_viaRecorder() {
        OrgRoutingConfig cfg = newConfig();
        when(configRepository.findByOrgId(ORG)).thenReturn(Optional.of(cfg));

        service.resolveAgentId(ORG, "user-1", "anything");

        verify(recorder).recordDecision(
                eq(ORG), eq("user-1"), eq(null), eq("anything"),
                eq(null),
                eq(RoutingDecisionEntity.StrategyUsed.NONE),
                eq(null), eq(0), anyLong(), eq(null));
    }

    @Test
    void resolveAgentId_blankOrg_doesNotInvokeRecorder() {
        service.resolveAgentId("  ", "user-1", "msg");
        verifyNoInteractions(recorder);
    }
}
