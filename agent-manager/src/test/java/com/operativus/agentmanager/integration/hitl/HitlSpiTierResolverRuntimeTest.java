package com.operativus.agentmanager.integration.hitl;

import com.operativus.agentmanager.compute.advisor.HitlAdvisor;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.exception.ApprovalRequiredException;
import com.operativus.agentmanager.core.model.DecisionPackage;
import com.operativus.agentmanager.core.spi.ToolTierResolverProvider;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin the {@link ToolTierResolverProvider} SPI dispatch wired into
 *   {@code HitlAdvisor}. The SPI exists to prevent {@code compute/advisor/} from depending
 *   on {@code compute/tools/composio/} (audit Finding 7); production registers
 *   {@code ComposioTierResolver} as the only SPI bean today. {@code HitlAdvisor} iterates
 *   the autowired {@code List<ToolTierResolverProvider>} in order and uses the first
 *   non-empty resolution, falling through to its static {@code DESTRUCTIVE_TOOLS} /
 *   {@code FINOPS_TOOLS} sets when every provider returns {@link Optional#empty()}.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Coverage gap before this test:
 *   - Unit test {@code HitlAdvisorTest} mocks the entire approval service and never
 *     exercises the SPI path.
 *   - {@code ComposioHotReloadCompositeRuntimeTest} pins the Composio resolver's DB-reload
 *     behaviour but not the {@code HitlAdvisor} dispatch.
 *   - No runtime test verified: (a) SPI Tier-3 wins for a tool NOT in static set, (b) SPI
 *     {@code Optional.empty()} falls through to static set, (c) {@code agent.hitl.composio_dispatch}
 *     Counter increments on each provider hit, (d) first-non-empty-wins ordering across
 *     multiple providers.
 *
 * Why a runtime test (vs unit-test extension):
 *   Verifying that Spring autowires the test providers into HitlAdvisor's constructor list
 *   alongside the production {@code ComposioTierResolver} bean — and that the
 *   {@code MeterRegistry} bean is wired correctly so the counter increments — both require
 *   the live Spring context. The dispatch-decision logic itself is also covered as a
 *   side-effect; the value here is contract-of-the-wiring, not algorithm verification.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        HitlSpiTierResolverRuntimeTest.TestSpiConfig.class})
public class HitlSpiTierResolverRuntimeTest extends BaseIntegrationTest {

    private static final String SPI_TIER3_TOOL = "spi-test-tool-tier3-only";
    private static final String UNKNOWN_TOOL = "spi-test-tool-not-recognized-by-any-resolver";
    private static final String STATIC_DESTRUCTIVE_TOOL = "delete_database";  // in HitlAdvisor.DESTRUCTIVE_TOOLS

    @Autowired private HitlAdvisor hitlAdvisor;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private RecordingTierResolver recordingResolver;

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        recordingResolver.reset();
    }

    // P1.2-1 — SPI Tier-3 wins for a tool that is NOT in the static DESTRUCTIVE_TOOLS or
    // FINOPS_TOOLS sets. Without the SPI dispatch the tool would route to TIER_1_SAFE and
    // auto-approve, defeating the entire point of having an extensible HITL tier policy.
    @Test
    void spiProviderReturnsTier3_winsOverStaticFallthrough() {
        String runId = "run-" + UUID.randomUUID();
        String sessionId = seedAgentSession("spi-tier3");
        String agentId = jdbc.queryForObject(
                "SELECT agent_id FROM agent_sessions WHERE session_id = ?", String.class, sessionId);

        ApprovalRequiredException ex = bindOrgIdAnd("org-spi-1", () ->
                assertThrows(ApprovalRequiredException.class, () ->
                        hitlAdvisor.requireApprovalForTool(
                                SPI_TIER3_TOOL, "{}", runId, sessionId, agentId)));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, decision_tier FROM approvals WHERE id = ?", ex.getApprovalId());

        assertAll("SPI Tier-3 overrides static-set fallthrough",
                () -> assertEquals("TIER_3_DESTRUCTIVE", row.get("decision_tier"),
                        "SPI provider must own the routing decision for tools it recognizes — "
                                + "if the static fallthrough fired, the tool would have routed to TIER_1_SAFE"),
                () -> assertEquals("PENDING", row.get("status"),
                        "Tier-3 routing must produce a PENDING row (only Tier-1 auto-approves)"));
    }

    // P1.2-2 — When every SPI provider returns Optional.empty for a tool name, HitlAdvisor
    // must fall through to its built-in DESTRUCTIVE_TOOLS / FINOPS_TOOLS / TIER_1_SAFE
    // classification. The recording resolver returns empty for all non-SPI_TIER3_TOOL inputs;
    // we use a known DESTRUCTIVE_TOOLS member ("delete_database") so we can pin the
    // fall-through result.
    @Test
    void spiProvidersReturnEmpty_fallsThroughToStaticDestructiveSet() {
        String runId = "run-" + UUID.randomUUID();
        String sessionId = seedAgentSession("spi-fallthrough");
        String agentId = jdbc.queryForObject(
                "SELECT agent_id FROM agent_sessions WHERE session_id = ?", String.class, sessionId);

        // Snapshot counter before — Composio's resolver may legitimately respond to other
        // probes happening on the live context; delta-vs-snapshot is the only safe
        // assertion shape.
        long counterBefore = readDispatchCounter();
        int recordingCallsBefore = recordingResolver.callCount();

        ApprovalRequiredException ex = bindOrgIdAnd("org-spi-2", () ->
                assertThrows(ApprovalRequiredException.class, () ->
                        hitlAdvisor.requireApprovalForTool(
                                STATIC_DESTRUCTIVE_TOOL, "{}", runId, sessionId, agentId)));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT decision_tier FROM approvals WHERE id = ?", ex.getApprovalId());

        long counterAfter = readDispatchCounter();
        int recordingCallsAfter = recordingResolver.callCount();

        assertAll("empty SPI resolution falls through to static set",
                () -> assertEquals("TIER_3_DESTRUCTIVE", row.get("decision_tier"),
                        "delete_database is in HitlAdvisor.DESTRUCTIVE_TOOLS — fall-through must route it to TIER_3"),
                () -> assertTrue(recordingCallsAfter > recordingCallsBefore,
                        "recording resolver MUST have been consulted before fall-through fired"),
                () -> assertEquals(counterBefore, counterAfter,
                        "dispatchCounter must not increment on an empty SPI resolution — counter is for SPI hits only"));
    }

    // P1.2-3 — agent.hitl.composio_dispatch Counter must increment exactly once per SPI hit.
    // The counter is the observability hook for "how much of HITL tier resolution is driven
    // by extensible SPIs vs the hardcoded static sets" — if the counter never moves, future
    // operator dashboards will silently report 0% SPI usage.
    @Test
    void spiDispatchCounter_incrementsOncePerProviderHit() {
        String runId1 = "run-" + UUID.randomUUID();
        String runId2 = "run-" + UUID.randomUUID();
        String sessionId = seedAgentSession("spi-counter");
        String agentId = jdbc.queryForObject(
                "SELECT agent_id FROM agent_sessions WHERE session_id = ?", String.class, sessionId);

        long counterBefore = readDispatchCounter();

        bindOrgIdAnd("org-spi-3", () -> {
            assertThrows(ApprovalRequiredException.class, () ->
                    hitlAdvisor.requireApprovalForTool(
                            SPI_TIER3_TOOL, "{}", runId1, sessionId, agentId));
            return null;
        });
        bindOrgIdAnd("org-spi-3", () -> {
            assertThrows(ApprovalRequiredException.class, () ->
                    hitlAdvisor.requireApprovalForTool(
                            SPI_TIER3_TOOL, "{}", runId2, sessionId, agentId));
            return null;
        });

        long counterAfter = readDispatchCounter();
        assertEquals(2L, counterAfter - counterBefore,
                "dispatchCounter must increment exactly once per SPI hit — observed delta does not match "
                        + "the two SPI-resolved dispatches this test made");
    }

    // P1.2-4 — Multiple providers iterate in order; first non-empty wins. The recording
    // resolver is @Order(0) and resolves SPI_TIER3_TOOL. The second resolver
    // (NeverInvokedResolver, @Order(10)) increments a probe counter on every call. After
    // the SPI hit, the second resolver's probe count must NOT have advanced for
    // SPI_TIER3_TOOL — proving HitlAdvisor short-circuited on the first hit.
    @Test
    void multipleSpiProviders_firstNonEmptyWins_secondNotConsulted() {
        String runId = "run-" + UUID.randomUUID();
        String sessionId = seedAgentSession("spi-order");
        String agentId = jdbc.queryForObject(
                "SELECT agent_id FROM agent_sessions WHERE session_id = ?", String.class, sessionId);

        int secondCallsBefore = NeverInvokedResolver.timesCalledForTier3Tool.get();

        bindOrgIdAnd("org-spi-4", () -> {
            assertThrows(ApprovalRequiredException.class, () ->
                    hitlAdvisor.requireApprovalForTool(
                            SPI_TIER3_TOOL, "{}", runId, sessionId, agentId));
            return null;
        });

        int secondCallsAfter = NeverInvokedResolver.timesCalledForTier3Tool.get();
        assertEquals(secondCallsBefore, secondCallsAfter,
                "When the first SPI provider returns non-empty, HitlAdvisor must NOT consult later "
                        + "providers — short-circuit is the explicit contract in resolveTierFromProviders");
    }

    // ─── helpers ───

    private String seedAgentSession(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "SPI Test Agent " + label);
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", label + "-user", agentId);
        return sessionId;
    }

    private long readDispatchCounter() {
        Counter c = meterRegistry.find("agent.hitl.composio_dispatch").counter();
        assertNotNull(c, "agent.hitl.composio_dispatch counter must be registered by HitlAdvisor's constructor");
        return (long) c.count();
    }

    private static <T> T bindOrgIdAnd(String orgId, java.util.concurrent.Callable<T> body) {
        try {
            return ScopedValue.where(AgentContextHolder.orgId, orgId).call(body::call);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    // ─── test SPI providers ───

    /**
     * Test bean wiring. Registered via {@code @Import} on the test class.
     * {@code RecordingTierResolver} is {@code @Order(0)} so it sees every lookup first;
     * {@code NeverInvokedResolver} is {@code @Order(10)} to guarantee it runs after.
     * Production's {@code ComposioTierResolver} has no explicit {@code @Order} (defaults to
     * lowest precedence) — when our test resolver returns non-empty, neither this Composio
     * resolver nor {@code NeverInvokedResolver} should be consulted further.
     */
    @TestConfiguration
    static class TestSpiConfig {
        @Bean
        @Order(0)
        RecordingTierResolver recordingTierResolver() {
            return new RecordingTierResolver();
        }

        @Bean
        @Order(10)
        NeverInvokedResolver neverInvokedResolver() {
            return new NeverInvokedResolver();
        }
    }

    /**
     * Returns Tier-3 only for {@link #SPI_TIER3_TOOL}; empty otherwise. Tracks call count so
     * the fall-through case can assert it WAS consulted before HitlAdvisor moved to the
     * static set.
     */
    public static class RecordingTierResolver implements ToolTierResolverProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<DecisionPackage.DecisionTier> resolveTier(String toolName) {
            calls.incrementAndGet();
            if (SPI_TIER3_TOOL.equals(toolName)) {
                return Optional.of(DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE);
            }
            return Optional.empty();
        }

        public int callCount() {
            return calls.get();
        }

        public void reset() {
            calls.set(0);
        }
    }

    /**
     * Provider that should NEVER be consulted for {@link #SPI_TIER3_TOOL} because the
     * higher-priority {@code RecordingTierResolver} short-circuits on it. If HitlAdvisor's
     * short-circuit contract regresses, this counter will advance for the Tier-3 tool name.
     * Uses a static counter (not instance state) so the assertion can use a class reference.
     */
    public static class NeverInvokedResolver implements ToolTierResolverProvider {
        static final AtomicInteger timesCalledForTier3Tool = new AtomicInteger();

        @Override
        public Optional<DecisionPackage.DecisionTier> resolveTier(String toolName) {
            if (SPI_TIER3_TOOL.equals(toolName)) {
                timesCalledForTier3Tool.incrementAndGet();
            }
            return Optional.empty();
        }
    }
}
