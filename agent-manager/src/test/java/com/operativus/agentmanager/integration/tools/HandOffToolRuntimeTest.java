package com.operativus.agentmanager.integration.tools;

import com.operativus.agentmanager.compute.tools.HandOffTool;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.repository.TransitionEdgeRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.entity.TransitionEdge;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.exception.SwarmEscalationException;
import com.operativus.agentmanager.core.exception.SwarmHandOffException;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Boot-context runtime coverage of
 *   {@link com.operativus.agentmanager.compute.tools.HandOffTool#hand_off_to_agent}
 *   — the Swarm-style voluntary handoff primitive that an agent invokes (as a
 *   Spring AI tool) to surrender control to a peer.
 *
 *   The unit-level {@link com.operativus.agentmanager.compute.tools.HandOffToolTest}
 *   stubs both validators with Mockito. These tests run the REAL Spring-wired
 *   {@code TransitionValidator} + {@code TierEscalationValidator} against
 *   real persisted agents in pgvector to prove the production wiring throws
 *   {@link SwarmHandOffException} on success and {@link SwarmEscalationException}
 *   on upward tier transitions.
 *
 *   Originally planned as {@code SwarmHandOffRuntimeTest} (full Swarm + handoff
 *   composition), narrowed here to the tool's own contract because the
 *   composition path requires LLM tool-call simulation (member agent's chat
 *   model emits a hand_off_to_agent tool call) which is covered separately as
 *   part of the cross-orchestrator composition PR. This file pins the tool's
 *   own contract; the Swarm-with-real-handoff path is a composition test.
 *
 *   Cases covered:
 *     1. happy path — both validators pass, source bound, target valid: throws
 *        SwarmHandOffException carrying targetAgentId + handOffContext.
 *     2. tier escalation — source tier-1, target tier-3: throws
 *        SwarmEscalationException with both agent ids + tier integers (real
 *        TierEscalationValidator computed from securityTier columns).
 *     3. unbound source — agentId ScopedValue not bound: TierEscalationValidator
 *        is NOT invoked (line 53 of HandOffTool — guarded by null check), so the
 *        success path still terminates via SwarmHandOffException.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class})
public class HandOffToolRuntimeTest extends BaseIntegrationTest {

    @Autowired private HandOffTool handOffTool;
    @Autowired private AgentRepository agentRepository;
    @Autowired private TransitionEdgeRepository edgeRepository;

    @BeforeEach
    void seedFixtures() {
        seedDefaultModel();
    }

    /**
     * Case 1 — happy path. Source bound to a real tier-1 agent; target is a real
     * tier-1 peer (same tier → no escalation). Tool MUST throw
     * SwarmHandOffException carrying the target id + handoff context.
     */
    @Test
    void handOff_realValidators_validSameTierTransition_throwsSwarmHandOffException() {
        String sourceAgentId = persistAgent("handoff-source-" + UUID.randomUUID(), "Source", 1);
        String targetAgentId = persistAgent("handoff-target-" + UUID.randomUUID(), "Target", 1);

        SwarmHandOffException ex = runWithSourceAndAssertThrows(SwarmHandOffException.class, sourceAgentId,
                () -> handOffTool.hand_off_to_agent(targetAgentId, "context-payload"));

        assertAll("handoff happy path",
                () -> assertEquals(targetAgentId, ex.getTargetAgentId(),
                        "exception carries target agent id verbatim"),
                () -> assertEquals("context-payload", ex.getHandOffContext(),
                        "exception carries handoff context verbatim"));
    }

    /**
     * Case 2 — tier escalation blocked. Source is tier-1, target is tier-3. The
     * REAL TierEscalationValidator (loaded from Spring) consults the persisted
     * securityTier columns and throws SwarmEscalationException carrying both
     * tier integers — proving the validator wiring + DB read is correct.
     */
    @Test
    void handOff_realValidators_tier1ToTier3_throwsSwarmEscalationException() {
        String sourceAgentId = persistAgent("handoff-tier1-" + UUID.randomUUID(), "Source Tier 1", 1);
        String privilegedTargetId = persistAgent("handoff-tier3-" + UUID.randomUUID(), "Target Tier 3", 3);

        SwarmEscalationException ex = runWithSourceAndAssertThrows(SwarmEscalationException.class, sourceAgentId,
                () -> handOffTool.hand_off_to_agent(privilegedTargetId, "needs-MFA-tier-work"));

        assertAll("handoff tier escalation blocked",
                () -> assertEquals(sourceAgentId, ex.getSourceAgentId(),
                        "exception carries the source agent id"),
                () -> assertEquals(privilegedTargetId, ex.getTargetAgentId(),
                        "exception carries the target agent id"),
                () -> assertEquals(1, ex.getSourceTier(),
                        "real TierEscalationValidator read sourceTier=1 from DB"),
                () -> assertEquals(3, ex.getTargetTier(),
                        "real TierEscalationValidator read targetTier=3 from DB"));
    }

    /**
     * Case 3 — unbound source ScopedValue. {@code AgentContextHolder.getAgentId()}
     * returns null, so HandOffTool's guard at line 53 skips the tier check. The
     * call still completes via SwarmHandOffException (the success signal). This
     * pins the "tool is callable from non-agent contexts" behavior.
     */
    @Test
    void handOff_unboundSource_skipsTierCheck_stillThrowsSwarmHandOffException() {
        // Even though the target is tier-3, no tier check runs because source is null.
        String privilegedTargetId = persistAgent("handoff-tier3-noctx-" + UUID.randomUUID(), "Target Tier 3", 3);

        // No ScopedValue.where(...) wrapping — agentId unbound.
        SwarmHandOffException ex = assertThrows(SwarmHandOffException.class,
                () -> handOffTool.hand_off_to_agent(privilegedTargetId, "ctx"));

        assertAll("handoff with unbound source",
                () -> assertEquals(privilegedTargetId, ex.getTargetAgentId(),
                        "still throws SwarmHandOffException"),
                () -> assertEquals("ctx", ex.getHandOffContext(),
                        "context still propagates to the exception"));
    }

    /**
     * Case 4 — teamRootId bound + matching DAG edge: real TransitionValidator
     * resolves the bound teamRootId, finds the registered source→target edge,
     * permits the transition. HandOffTool then progresses to throw
     * SwarmHandOffException (the success signal). Pins the wiring by
     * TeamOrchestrationEngine so a future drop regresses loudly to no-op DAG
     * validation through the handoff path.
     */
    @Test
    void handOff_teamRootIdBoundWithMatchingEdge_passesValidation_andThrowsHandOff() {
        String teamRootId = "team-handoff-" + UUID.randomUUID();
        seedTeam(teamRootId, "Handoff DAG — matching");
        String sourceAgentId = persistAgent("handoff-dag-src-" + UUID.randomUUID(), "Source", 1);
        String targetAgentId = persistAgent("handoff-dag-tgt-" + UUID.randomUUID(), "Target", 1);
        edgeRepository.save(new TransitionEdge(UUID.randomUUID().toString(),
                teamRootId, sourceAgentId, targetAgentId));

        SwarmHandOffException ex = runWithTeamContextAndAssertThrows(SwarmHandOffException.class,
                sourceAgentId, teamRootId,
                () -> handOffTool.hand_off_to_agent(targetAgentId, "edge-allowed"));

        assertEquals(targetAgentId, ex.getTargetAgentId(),
                "registered edge → validator permits → SwarmHandOffException carries target");
    }

    /**
     * Case 5 — teamRootId bound + NO edge for source→target: real TransitionValidator
     * throws BusinessValidationException. Unlike DelegationTool, HandOffTool has
     * NO catch for BusinessValidationException — the exception propagates as-is to
     * the caller, who is expected to render the LLM-facing rejection. Pins the
     * difference in propagation contract between the two tools.
     */
    @Test
    void handOff_teamRootIdBoundWithUnregisteredEdge_throwsBusinessValidationException_unwrapped() {
        String teamRootId = "team-handoff-" + UUID.randomUUID();
        seedTeam(teamRootId, "Handoff DAG — unregistered");
        String sourceAgentId = persistAgent("handoff-dag-src-" + UUID.randomUUID(), "Source", 1);
        String targetAgentId = persistAgent("handoff-dag-tgt-" + UUID.randomUUID(), "Target", 1);
        String otherAgentId = persistAgent("handoff-dag-other-" + UUID.randomUUID(), "Other", 1);
        // Register source→other; caller asks for source→target (NOT in set).
        edgeRepository.save(new TransitionEdge(UUID.randomUUID().toString(),
                teamRootId, sourceAgentId, otherAgentId));

        BusinessValidationException ex = runWithTeamContextAndAssertThrows(BusinessValidationException.class,
                sourceAgentId, teamRootId,
                () -> handOffTool.hand_off_to_agent(targetAgentId, "edge-not-allowed"));

        assertTrue(ex.getMessage().contains("DAG constraint violation"),
                "validator must surface 'DAG constraint violation' message. Actual: " + ex.getMessage());
    }

    private void seedTeam(String teamId, String name) {
        jdbc.update("""
                INSERT INTO teams (id, name, description, created_at, updated_at)
                VALUES (?, ?, 'HandOffToolRuntimeTest fixture', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, teamId, name);
    }

    /**
     * Variant of {@link #runWithSourceAndAssertThrows} that ALSO binds teamRootId —
     * the ScopedValue that {@link com.operativus.agentmanager.compute.service.TeamOrchestrationEngine}
     * binds at executeSync/executeStream entry. Exercises the handoff path through
     * the real {@link com.operativus.agentmanager.compute.teams.TransitionValidator}
     * with a bound team context.
     */
    @SuppressWarnings("unchecked")
    private <T extends RuntimeException> T runWithTeamContextAndAssertThrows(Class<T> expectedClass,
                                                                              String sourceAgentId,
                                                                              String teamRootId,
                                                                              Runnable body) {
        Holder<RuntimeException> holder = new Holder<>();
        ScopedValue
                .where(AgentContextHolder.agentId, sourceAgentId)
                .where(AgentContextHolder.teamRootId, teamRootId)
                .where(AgentContextHolder.orgId, TenantConstants.DEFAULT_SYSTEM_ORG)
                .run(() -> {
                    try {
                        body.run();
                    } catch (RuntimeException re) {
                        holder.captured = re;
                    }
                });
        if (holder.captured == null) {
            throw new AssertionError("Expected " + expectedClass.getSimpleName() + " but no exception was thrown");
        }
        if (!expectedClass.isInstance(holder.captured)) {
            throw new AssertionError("Expected " + expectedClass.getSimpleName() + " but got " +
                    holder.captured.getClass().getSimpleName() + ": " + holder.captured.getMessage(),
                    holder.captured);
        }
        return (T) holder.captured;
    }

    /**
     * Run {@code body} under a ScopedValue binding for agentId + orgId, and
     * assert that it throws an exception of the given class. Returns the captured
     * exception for further inspection.
     */
    @SuppressWarnings("unchecked")
    private <T extends RuntimeException> T runWithSourceAndAssertThrows(Class<T> expectedClass,
                                                                        String sourceAgentId, Runnable body) {
        Holder<RuntimeException> holder = new Holder<>();
        ScopedValue
                .where(AgentContextHolder.agentId, sourceAgentId)
                .where(AgentContextHolder.orgId, TenantConstants.DEFAULT_SYSTEM_ORG)
                .run(() -> {
                    try {
                        body.run();
                    } catch (RuntimeException re) {
                        holder.captured = re;
                    }
                });
        if (holder.captured == null) {
            throw new AssertionError("Expected " + expectedClass.getSimpleName() + " but no exception was thrown");
        }
        if (!expectedClass.isInstance(holder.captured)) {
            throw new AssertionError("Expected " + expectedClass.getSimpleName() + " but got " +
                    holder.captured.getClass().getSimpleName() + ": " + holder.captured.getMessage(),
                    holder.captured);
        }
        return (T) holder.captured;
    }

    private static final class Holder<T> {
        T captured;
    }

    private String persistAgent(String id, String name, int securityTier) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("handoff-runtime fixture: " + name);
        a.setInstructions("handoff-runtime fixture instructions");
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
}
