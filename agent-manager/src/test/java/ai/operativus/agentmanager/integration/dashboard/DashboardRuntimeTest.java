package ai.operativus.agentmanager.integration.dashboard;

import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the dashboard's primary aggregation
 *   endpoint, {@code GET /api/monitoring/stats}. Boots the full app context and verifies the
 *   {@code totalAgents} / {@code totalActiveRuns} / {@code totalCompletedRuns} counters
 *   plus the (currently missing) tenant scoping for {@code totalAgents}.
 * State: Stateless (per-test isolation comes from {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §4.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link ai.operativus.agentmanager.control.service.MonitoringService#getGlobalStats()}
 *     counts only {@code RunStatus.RUNNING} toward {@code totalActiveRuns} — {@code QUEUED}
 *     is NOT included today, even though §4.4 lists both. Case 4 below pins the actual behavior.
 *   - <b>Tenant scoping for {@code totalAgents} IS enforced</b> via
 *     {@link ai.operativus.agentmanager.control.registry.DatabaseAgentRegistry#findAll(boolean)},
 *     which calls {@code agentRepository.findByOrgIdAndActiveTrue(orgId)}. The {@code orgId} is
 *     resolved from {@link ai.operativus.agentmanager.core.callback.AgentContextHolder#getOrgId()},
 *     which {@link ai.operativus.agentmanager.control.security.TenantContextFilter} binds for the
 *     duration of each request from the JWT claim or the {@code X-Org-Id} header. The Hibernate
 *     {@code @Filter("tenantFilter")} mechanism that previously decorated {@code AgentEntity}
 *     was removed (it never reliably activated under Spring Boot 4 OSIV); the explicit
 *     {@code findByOrgIdAndActiveTrue} call replaced it. §4.3 below ratifies the current
 *     correctly-scoped behavior. (Historical: the assertion was previously pinned at 5L/5L to
 *     catch a regression that the Hibernate-filter removal caused; that regression has been
 *     fixed by the explicit org-scoped repository call.)
 *   - The seed Liquibase changeset {@code 002-seed-data.sql} inserts 4 agents with
 *     {@code org_id = NULL} (procurator_assistant, finance_agent, investment_team, web_scraper).
 *     {@link #resetStateBeforeTest()} truncates them so each test starts from an empty
 *     {@code agents} table and asserts on its own deltas.
 *   - {@code spring.cache.type=none} in {@code application-test.properties} disables
 *     {@code @Cacheable("allAgents")} in {@code DatabaseAgentRegistry.findAll(boolean)}.
 *     The production {@code CacheConfig} is gated on {@code spring.cache.type=redis} so it
 *     no longer overrides the property in the test profile. Without that, the cache pinned
 *     the first {@code findAll} result and ignored subsequent {@code X-Org-Id} contexts —
 *     even with explicit per-org query methods, a cached result would mask tenant scoping
 *     across tests; a no-op forces every call to round-trip the DB.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, TestData.class})
public class DashboardRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> STATS_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired private TestData testData;

    /**
     * Truncate before-test in addition to after-test: the FIRST test of the class boots into
     * a DB still populated by the Liquibase seed ({@code 002-seed-data.sql} inserts 4 agents
     * with {@code org_id=NULL}), which would otherwise leak into the count assertions below.
     */
    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // §4.1 — fresh org returns the documented shape with zero counts for that org's slice.
    // (Run counters are global, so we only assert totalAgents=0 here. The shape must always be present.)
    @Test
    void freshOrgScopeReturnsZeroAgentCountAndExpectedShape() {
        HttpHeaders auth = orgScopedAuth("dash-empty", "dash-empty@test.local", uniqueOrg("empty"));

        Map<String, Object> stats = getStats(auth);

        assertEquals(0L, asLong(stats.get("totalAgents")),
                "fresh fixture: truncate clears the seed rows and no test agents have been created yet");
        assertNotNull(stats.get("totalActiveRuns"), "totalActiveRuns must be present (numeric)");
        assertNotNull(stats.get("totalCompletedRuns"), "totalCompletedRuns must be present (numeric)");
        assertNotNull(stats.get("agentStats"), "agentStats array must be present");
        assertTrue(((List<?>) stats.get("agentStats")).isEmpty(),
                "no agents in this org → empty agentStats array");
    }

    // §4.2 — 3 agents + 2 RUNNING + 1 FAILED + 1 COMPLETED reflect into the counters
    @Test
    void seededAgentsAndRunsAreReflectedInGlobalStats() {
        String org = uniqueOrg("seed");
        String agentA = testData.agentForOrg("dash-agent-a", org).getId();
        testData.agentForOrg("dash-agent-b", org);
        testData.agentForOrg("dash-agent-c", org);

        HttpHeaders auth = orgScopedAuth("dash-seed", "dash-seed@test.local", org);
        long activeBefore = asLong(getStats(auth).get("totalActiveRuns"));
        long completedBefore = asLong(getStats(auth).get("totalCompletedRuns"));

        testData.run(agentA, org, RunStatus.RUNNING);
        testData.run(agentA, org, RunStatus.RUNNING);
        testData.run(agentA, org, RunStatus.FAILED);
        testData.run(agentA, org, RunStatus.COMPLETED);

        Map<String, Object> stats = getStats(auth);

        assertEquals(3L, asLong(stats.get("totalAgents")),
                "the 3 agents we inserted under this org are the only ones the org-scoped count returns (findByOrgIdAndActiveTrue — see class Javadoc)");
        assertEquals(activeBefore + 2L, asLong(stats.get("totalActiveRuns")),
                "RUNNING runs must contribute to totalActiveRuns (delta of 2)");
        assertEquals(completedBefore + 1L, asLong(stats.get("totalCompletedRuns")),
                "COMPLETED runs must contribute to totalCompletedRuns (delta of 1)");
    }

    // §4.3 — ratifies the CURRENT (correctly-scoped) tenant behavior end-to-end.
    // MonitoringService.getGlobalStats() calls agentRegistry.findAll(false), which under
    // DatabaseAgentRegistry calls agentRepository.findByOrgIdAndActiveTrue(orgId) with
    // orgId resolved from AgentContextHolder.getOrgId() — bound per-request by
    // TenantContextFilter from the JWT claim or X-Org-Id header. Was previously pinned at
    // 5L/5L (broken-behavior regression-pin); the regression that pinned has been fixed by
    // the explicit org-scoped repository call. Flipped to ratify what production now does.
    @Test
    void agentCountsAreCurrentlyGlobalAcrossOrgsPendingTenantFilterFix() {
        String orgA = uniqueOrg("tenantA");
        String orgB = uniqueOrg("tenantB");
        testData.agentForOrg("a-1", orgA);
        testData.agentForOrg("a-2", orgA);
        testData.agentForOrg("b-1", orgB);
        testData.agentForOrg("b-2", orgB);
        testData.agentForOrg("b-3", orgB);

        HttpHeaders authA = orgScopedAuth("dash-tenantA", "dash-tenantA@test.local", orgA);
        Map<String, Object> statsA = getStats(authA);
        assertEquals(2L, asLong(statsA.get("totalAgents")),
                "tenant scoping ratified: orgA sees ONLY its 2 agents (orgB rows hidden by findByOrgIdAndActiveTrue + TenantContextFilter)");

        HttpHeaders authB = orgScopedAuth("dash-tenantB", "dash-tenantB@test.local", orgB);
        Map<String, Object> statsB = getStats(authB);
        assertEquals(3L, asLong(statsB.get("totalAgents")),
                "tenant scoping ratified: orgB sees ONLY its 3 agents (orgA rows hidden by the same path)");
    }

    // §4.4 — RUNNING-only filter for totalActiveRuns. Pins the current implementation: QUEUED
    // does NOT count toward active, and terminal states (COMPLETED/FAILED/CANCELLED) do not either.
    @Test
    void queuedAndTerminalRunsAreExcludedFromTotalActiveRuns() {
        String org = uniqueOrg("status");
        String agent = testData.agentForOrg("dash-status-agent", org).getId();

        HttpHeaders auth = orgScopedAuth("dash-status", "dash-status@test.local", org);
        long activeBefore = asLong(getStats(auth).get("totalActiveRuns"));
        long completedBefore = asLong(getStats(auth).get("totalCompletedRuns"));

        testData.run(agent, org, RunStatus.RUNNING);
        testData.run(agent, org, RunStatus.QUEUED);
        testData.run(agent, org, RunStatus.QUEUED);
        testData.run(agent, org, RunStatus.COMPLETED);
        testData.run(agent, org, RunStatus.FAILED);
        testData.run(agent, org, RunStatus.CANCELLED);

        Map<String, Object> stats = getStats(auth);

        assertEquals(activeBefore + 1L, asLong(stats.get("totalActiveRuns")),
                "MonitoringService.getGlobalStats counts only RunStatus.RUNNING — QUEUED is excluded today");
        assertEquals(completedBefore + 1L, asLong(stats.get("totalCompletedRuns")),
                "only the single COMPLETED run contributes to totalCompletedRuns");
    }

    // §4.5 — fresh GET after creating a new agent reflects the new count immediately (no stale cache)
    @Test
    void freshGetAfterMutationReflectsTheNewAgent() {
        String org = uniqueOrg("cache");
        HttpHeaders auth = orgScopedAuth("dash-cache", "dash-cache@test.local", org);

        long before = asLong(getStats(auth).get("totalAgents"));

        testData.agentForOrg("freshly-added", org);

        long after = asLong(getStats(auth).get("totalAgents"));
        assertEquals(before + 1L, after,
                "next GET must observe the newly-added agent — spring.cache.type=none in the test profile");
    }

    // ─── helpers ───

    private Map<String, Object> getStats(HttpHeaders auth) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/monitoring/stats"), HttpMethod.GET, new HttpEntity<>(auth), STATS_TYPE);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private HttpHeaders orgScopedAuth(String username, String email, String orgId) {
        // GET /api/monitoring/stats is class-level hasRole('ADMIN') since #809 (security gate), so the
        // caller must hold ROLE_ADMIN. registerLoginWithOrg issues a ROLE_USER+ROLE_ADMIN JWT whose
        // org_id claim is orgId — TenantContextFilter binds that into AgentContextHolder.getOrgId(),
        // which is the production tenant-scoping path (findByOrgIdAndActiveTrue) the count assertions
        // rely on. (The older X-Org-Id-header shortcut no longer drives scoping: the JWT claim wins.)
        // email is derived inside registerLoginWithOrg; kept on the signature for call-site clarity.
        return registerLoginWithOrg(username, orgId);
    }

    private static String uniqueOrg(String label) {
        return "org-dash-" + label + "-" + UUID.randomUUID();
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        throw new AssertionError("expected numeric stats value, got " + value);
    }
}
