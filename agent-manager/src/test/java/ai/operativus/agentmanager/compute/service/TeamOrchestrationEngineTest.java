package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.compute.teams.OrchestrationStrategy;
import ai.operativus.agentmanager.control.repository.SessionRepository;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.EventType;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import ai.operativus.agentmanager.core.registry.RunOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Domain Responsibility: Pins the resolveStrategy + executeStream contract added by Tier 2.5 F4 —
 *   strategy resolution must happen synchronously BEFORE any side effects (session create, run-row
 *   save), so an invalid teamMode never leaks a permanent RUNNING agent_runs row and never emits
 *   misleading START/TOOL_START stream events.
 *
 * State: Stateless (Mockito mocks per @BeforeEach).
 *
 * Coverage targets:
 *   T-F4-1 — executeStream with unknown teamMode returns Flux.error and never invokes runRepository.save
 *   T-F4-2 — executeStream with null teamMode returns Flux.error (defends against the historical
 *            NPE at strategy.toUpperCase() in the bug-prior code) and never invokes runRepository.save
 *   T-F4-3 — executeSync with null teamMode throws the same actionable UnsupportedOperationException
 *            (folded-in bonus — the resolveStrategy null-guard protects both paths)
 *   T-F4-4 — executeStream with invalid teamMode does NOT emit START or TOOL_START events; subscriber
 *            sees onError as the FIRST signal (intentional behavioral change documented in spec §4c)
 */
@ExtendWith(MockitoExtension.class)
class TeamOrchestrationEngineTest {

    @Mock private RunOperations runRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private AgentRunFinalizer finalizer;
    @Mock private AgentOperations agentService;

    private TeamOrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        // Empty strategy list — the strategies map is empty. resolveStrategy("ANYTHING") returns
        // null → throws UnsupportedOperationException. Sufficient for the F4 contracts under test.
        engine = new TeamOrchestrationEngine(
                Collections.<OrchestrationStrategy>emptyList(),
                runRepository,
                sessionRepository,
                finalizer);
    }

    @Test
    void executeStream_unknownTeamMode_returnsFluxErrorWithoutCreatingRun() {
        AgentDefinition def = team("BOGUS");

        Flux<AgentStreamEvent> flux = engine.executeStream(
                def, "input", null, "sess-1", "user-1", "org-1",
                false, agentService, 0, null);

        StepVerifier.create(flux)
                .expectErrorSatisfies(t -> {
                    assertTrue(t instanceof UnsupportedOperationException,
                            "must surface UnsupportedOperationException, got " + t.getClass());
                    assertTrue(t.getMessage().contains("BOGUS"),
                            "error message must include the offending teamMode for operator triage; got: " + t.getMessage());
                })
                .verify();

        verify(runRepository, never()).save(any(AgentRun.class));
        verify(finalizer, never()).finalizeRun(any(), any(), any(), any(), any());
    }

    @Test
    void executeStream_nullTeamMode_returnsFluxErrorWithoutCreatingRun() {
        AgentDefinition def = team(null);

        Flux<AgentStreamEvent> flux = engine.executeStream(
                def, "input", null, "sess-2", "user-2", "org-2",
                false, agentService, 0, null);

        StepVerifier.create(flux)
                .expectErrorSatisfies(t -> {
                    assertTrue(t instanceof UnsupportedOperationException,
                            "null teamMode must surface UnsupportedOperationException, NOT NullPointerException; got " + t.getClass());
                    assertTrue(t.getMessage().toLowerCase().contains("teammode"),
                            "error message must reference the missing teamMode field for operator triage; got: " + t.getMessage());
                })
                .verify();

        verify(runRepository, never()).save(any(AgentRun.class));
        verify(finalizer, never()).finalizeRun(any(), any(), any(), any(), any());
    }

    @Test
    void executeSync_nullTeamMode_throwsActionableError() {
        // The resolveStrategy null-guard introduced for F4 also protects executeSync. Without it,
        // executeSync with a null teamMode AgentDefinition NPEs at teamMode.toUpperCase() with no
        // operator-actionable message. With the guard, both paths share the same UoE shape.
        AgentDefinition def = team(null);
        AgentRun runRecord = new AgentRun(def.id(), "sess-3", "input", "user-3", "org-3");
        runRecord.setId("run-3");

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> engine.executeSync(def, "input", null, "sess-3", "user-3", "org-3",
                        false, agentService, "run-3", runRecord, 0));
        assertTrue(ex.getMessage().toLowerCase().contains("teammode"),
                "error must reference the missing teamMode for triage; got: " + ex.getMessage());
        verify(runRepository, never()).save(any(AgentRun.class));
    }

    @Test
    void executeStream_invalidMode_doesNotEmitStartOrToolStart() {
        // Pre-F4 behavior: subscriber received START + TOOL_START events before onError.
        // Post-F4 behavior: subscriber sees onError as the FIRST signal because the synchronous
        // resolveStrategy throw aborts the method before Flux.create's lambda runs. This pins the
        // intentional ordering change from spec §4c.
        AgentDefinition def = team("BOGUS");

        Flux<AgentStreamEvent> flux = engine.executeStream(
                def, "input", null, "sess-4", "user-4", "org-4",
                false, agentService, 0, null);

        StepVerifier.create(flux)
                .expectError(UnsupportedOperationException.class)
                .verify();

        // Stronger assertion via collection: ensure no event was emitted before the error.
        Flux<AgentStreamEvent> flux2 = engine.executeStream(
                def, "input", null, "sess-4b", "user-4", "org-4",
                false, agentService, 0, null);
        flux2.onErrorResume(e -> Flux.empty())
                .doOnNext(evt -> {
                    if (evt.event() == EventType.START || evt.event() == EventType.TOOL_START) {
                        throw new AssertionError(
                                "F4: invalid teamMode must NOT emit START or TOOL_START before onError; saw " + evt.event());
                    }
                })
                .blockLast();
    }

    private static AgentDefinition team(String teamMode) {
        return new AgentDefinition(
                "team-1", "T", "D", "I", "gpt-x",
                null, null, null, null,
                false, true, teamMode,
                List.of("m-a", "m-b"),
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ai.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                false, null, null, null);
    }
}
