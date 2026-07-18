package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.TierEscalationValidator;
import com.operativus.agentmanager.compute.teams.TransitionValidator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.TransitionEdgeRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.TransitionEdge;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.SwarmEscalationException;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Direct runtime coverage of the two governance validators
 *   that protect orchestrator delegations:
 *   {@link com.operativus.agentmanager.compute.teams.TransitionValidator} — DAG
 *     edge enforcement (allow when no edges exist, allow on known edge, block
 *     unknown edge).
 *   {@link com.operativus.agentmanager.compute.teams.TierEscalationValidator} —
 *     security-tier boundary enforcement (throw on upward transitions; permit
 *     same-tier and downward).
 * State: Stateless (per-test isolation).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T034 (2 cases).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class OrchestratorValidatorsRuntimeTest extends BaseIntegrationTest {

    @Autowired private TransitionValidator transitionValidator;
    @Autowired private TierEscalationValidator tierEscalationValidator;
    @Autowired private AgentRepository agentRepository;
    @Autowired private TransitionEdgeRepository edgeRepository;

    @BeforeEach
    void seedFixtures() {
        seedDefaultModel();
    }

    /**
     * T034 case 1 — TransitionValidator DAG behavior (assertAll):
     *   (a) null teamId → no-op (silent pass).
     *   (b) team with zero edges → unconstrained mode (pass).
     *   (c) team with a registered edge → transition matching the edge passes.
     *   (d) team with edges registered but transition NOT in the edge set →
     *       throws BusinessValidationException with the "DAG constraint
     *       violation" message.
     */
    @Test
    void transitionValidator_dagBehavior_unconstrainedAllowAndBlock() {
        String teamWithEdges = "team-t034-" + UUID.randomUUID();
        String teamWithoutEdges = "team-t034-empty-" + UUID.randomUUID();
        // team_transition_edges.team_id FK references teams(id) — seed both team rows
        seedTeam(teamWithEdges, "Team With Edges");
        seedTeam(teamWithoutEdges, "Team Without Edges");

        String agentA = persistAgent("val-a-" + UUID.randomUUID(), "Agent A", 1);
        String agentB = persistAgent("val-b-" + UUID.randomUUID(), "Agent B", 1);
        String agentC = persistAgent("val-c-" + UUID.randomUUID(), "Agent C", 1);

        // Register a single A→B edge for the constrained team
        edgeRepository.save(new TransitionEdge(UUID.randomUUID().toString(),
                teamWithEdges, agentA, agentB));

        assertAll("transition validator behavior",
                () -> assertDoesNotThrow(
                        () -> transitionValidator.validate(null, agentA, agentB),
                        "null teamId is a no-op — DAG validation skipped"),
                () -> assertDoesNotThrow(
                        () -> transitionValidator.validate(teamWithoutEdges, agentA, agentB),
                        "team with zero edges → unconstrained mode, all transitions permitted"),
                () -> assertDoesNotThrow(
                        () -> transitionValidator.validate(teamWithEdges, agentA, agentB),
                        "registered A→B edge → transition matches → permitted"),
                () -> {
                    BusinessValidationException ex = assertThrows(
                            BusinessValidationException.class,
                            () -> transitionValidator.validate(teamWithEdges, agentA, agentC),
                            "A→C is NOT in the edge set → must throw");
                    assertTrue(ex.getMessage().contains("DAG constraint violation"),
                            "unknown edge must throw with 'DAG constraint violation': " + ex.getMessage());
                });
    }

    /**
     * T034 case 2 — TierEscalationValidator behavior (assertAll):
     *   (a) same tier → pass.
     *   (b) downward transition (higher→lower) → pass.
     *   (c) unknown source or target agent → fail-closed throw of
     *       {@link BusinessValidationException}. This was originally a silent
     *       pass (null-safe), but the security hardening fix
     *       {@code 84833de "fix(security): harden orchestrator dispatch — fail-closed
     *       tier validation"} flipped it: a missing-agent lookup is a configuration
     *       error, not implicit "OK". This test pins the fail-closed behavior so a
     *       future "fix" that re-introduces silent pass trips a deliberate failure.
     *   (d) upward transition (lower→higher) → throws SwarmEscalationException
     *       carrying the source/target ids and tiers.
     */
    @Test
    void tierEscalationValidator_permitsSameAndDownward_throwsOnUpward() {
        String tier1a = persistAgent("tier1a-" + UUID.randomUUID(), "Tier 1 A", 1);
        String tier1b = persistAgent("tier1b-" + UUID.randomUUID(), "Tier 1 B", 1);
        String tier3 = persistAgent("tier3-" + UUID.randomUUID(), "Tier 3", 3);
        String tier5 = persistAgent("tier5-" + UUID.randomUUID(), "Tier 5", 5);

        assertAll("tier escalation validator behavior",
                () -> assertDoesNotThrow(
                        () -> tierEscalationValidator.validate(tier1a, tier1b, TenantConstants.DEFAULT_SYSTEM_ORG),
                        "same-tier transition → permitted"),
                () -> assertDoesNotThrow(
                        () -> tierEscalationValidator.validate(tier5, tier1a, TenantConstants.DEFAULT_SYSTEM_ORG),
                        "downward transition (Tier 5 → Tier 1) → permitted"),
                () -> {
                    BusinessValidationException ex = assertThrows(
                            BusinessValidationException.class,
                            () -> tierEscalationValidator.validate("nonexistent-source", tier1a, TenantConstants.DEFAULT_SYSTEM_ORG),
                            "unknown source agent → fail-closed throw (security hardening 84833de)");
                    assertTrue(ex.getMessage().contains("source agent 'nonexistent-source' is unknown"),
                            "exception message identifies the unknown source: " + ex.getMessage());
                },
                () -> {
                    BusinessValidationException ex = assertThrows(
                            BusinessValidationException.class,
                            () -> tierEscalationValidator.validate(tier1a, "nonexistent-target", TenantConstants.DEFAULT_SYSTEM_ORG),
                            "unknown target agent → fail-closed throw (security hardening 84833de)");
                    assertTrue(ex.getMessage().contains("target agent 'nonexistent-target' is unknown"),
                            "exception message identifies the unknown target: " + ex.getMessage());
                },
                () -> {
                    SwarmEscalationException ex = assertThrows(
                            SwarmEscalationException.class,
                            () -> tierEscalationValidator.validate(tier1a, tier3, TenantConstants.DEFAULT_SYSTEM_ORG),
                            "Tier 1 → Tier 3 is upward → must throw SwarmEscalationException");
                    assertEquals(tier1a, ex.getSourceAgentId(),
                            "exception carries source agent id");
                    assertEquals(tier3, ex.getTargetAgentId(),
                            "exception carries target agent id");
                });
    }

    private String persistAgent(String id, String name, int securityTier) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("T034 fixture: " + name);
        a.setInstructions("T034 fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setSecurityTier(securityTier);
        return agentRepository.save(a).getId();
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void seedTeam(String teamId, String name) {
        jdbc.update("""
                INSERT INTO teams (id, name, description, created_at, updated_at)
                VALUES (?, ?, 'T034 fixture team', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, teamId, name);
    }
}
