package ai.operativus.agentmanager.integration.finops;

import ai.operativus.agentmanager.control.finops.exception.DailyBudgetExceededException;
import ai.operativus.agentmanager.control.finops.service.DailySpendService;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Domain Responsibility: Proves the org-level DAILY FinOps budget gate (DailySpendService) end to
 *   end. With agentmanager.finops.org-daily-cap-usd set, an org whose TODAY spend (summed from
 *   agent_runs.total_cost_usd) has reached the cap is rejected at run admission with HTTP 402
 *   BEFORE any new agent_runs row is created; an org under the cap runs normally. Complements the
 *   mid-flight per-session ceiling (GenAiMetricsAdvisor) — this is the daily window it lacked.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        OrgDailyBudgetCapRuntimeTest.FixedClockConfig.class})
@TestPropertySource(properties = "agentmanager.finops.org-daily-cap-usd=1.00")
public class OrgDailyBudgetCapRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * Pins DailySpendService's daily-window clock to a fixed UTC instant so the test never straddles
     * UTC midnight between seeding {@code agent_runs.created_at} and reading the windowed spend (the
     * previous {@code now()} + {@code LocalDate.now(UTC)} pairing flaked when the suite crossed
     * midnight). Seeds use {@link #SEEDED_AT}, which sits inside this fixed day.
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    private static final Instant FIXED_NOW = Instant.parse("2026-06-15T12:00:00Z");
    /** A timestamp inside the fixed clock's UTC day (>= start-of-day, < next midnight). */
    private static final String SEEDED_AT = "2026-06-15 10:00:00";

    @Autowired private DailySpendService dailySpendService;

    @Test
    void orgOverDailyCap_runRejected402_noNewRunRow_andUnderCapOrgRunsNormally() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String cappedOrg = "org-capped-" + tag;
        String username = "finops-daily-" + tag;

        HttpHeaders auth = registerLoginWithOrg(username, cappedOrg);
        seedModel();
        String agentId = "agent-daily-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "daily cap probe agent", cappedOrg);

        // Two completed runs TODAY summing to $1.20 — past the $1.00 cap.
        seedRunCostToday(cappedOrg, agentId, 0.80);
        seedRunCostToday(cappedOrg, agentId, 0.40);

        // Service-level: the org's today spend is read from agent_runs and the gate throws.
        org.junit.jupiter.api.Assertions.assertEquals(1.20, dailySpendService.currentDailySpendUsd(cappedOrg), 1e-9,
                "today spend must aggregate agent_runs.total_cost_usd for the org");
        org.junit.jupiter.api.Assertions.assertThrows(DailyBudgetExceededException.class,
                () -> dailySpendService.enforceOrgDailyCap(cappedOrg),
                "an org at/over its daily cap must be blocked at admission");

        long runsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE org_id = ?", Long.class, cappedOrg);

        // HTTP-level: the run is rejected 402 before a new run row is created.
        Map<String, Object> body = new HashMap<>();
        body.put("message", "should be blocked by the daily cap");
        body.put("sessionId", "sess-daily-" + tag);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.PAYMENT_REQUIRED, resp.getStatusCode(),
                "org over its daily cap must get 402 Payment Required");
        long runsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE org_id = ?", Long.class, cappedOrg);
        org.junit.jupiter.api.Assertions.assertEquals(runsBefore, runsAfter,
                "a capped run must be rejected BEFORE an agent_runs row is created");

        // An org under the cap is unaffected: its gate does not throw.
        String underOrg = "org-under-" + tag;
        seedRunCostToday(underOrg, agentId, 0.50);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> dailySpendService.enforceOrgDailyCap(underOrg),
                "an org below its daily cap must pass admission");
    }

    @Test
    void teamDailySpend_sumsMemberAgentRunsToday_excludesNonMembers_andEmptyIsZero() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String teamOrg = "org-team-" + tag;
        seedModel();
        String agentA = "agent-A-" + tag;
        String agentB = "agent-B-" + tag;
        String nonMember = "agent-X-" + tag;
        seedAgent(agentA, teamOrg);
        seedAgent(agentB, teamOrg);
        seedAgent(nonMember, teamOrg);

        seedRunCostToday(teamOrg, agentA, 0.30);
        seedRunCostToday(teamOrg, agentB, 0.70);
        seedRunCostToday(teamOrg, nonMember, 5.00); // not a team member — must be excluded

        // Native IN(:agentIds) sum: scoped to the two member agents only.
        org.junit.jupiter.api.Assertions.assertEquals(1.00,
                dailySpendService.currentTeamDailySpendUsd(java.util.List.of(agentA, agentB)), 1e-9,
                "team daily spend must sum only the member agents' runs for today");

        // Empty / null members never issue an IN () query and read as zero spend.
        org.junit.jupiter.api.Assertions.assertEquals(0.0,
                dailySpendService.currentTeamDailySpendUsd(java.util.List.of()), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(0.0,
                dailySpendService.currentTeamDailySpendUsd(null), 1e-9);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void seedAgent(String agentId, String orgId) {
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "team spend probe " + agentId, orgId);
    }

    private void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void seedRunCostToday(String orgId, String agentId, double costUsd) {
        // created_at is pinned inside the fixed clock's UTC day (see FixedClockConfig) so the
        // windowed-spend read is deterministic regardless of the real wall-clock at run time.
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, status, input, output,
                                        total_cost_usd, version, created_at, updated_at)
                VALUES (?, ?, ?, 'COMPLETED', 'seed input', 'seed output', ?, 0, ?::timestamp, ?::timestamp)
                """, "run-daily-" + UUID.randomUUID(), agentId, orgId, costUsd, SEEDED_AT, SEEDED_AT);
    }
}
