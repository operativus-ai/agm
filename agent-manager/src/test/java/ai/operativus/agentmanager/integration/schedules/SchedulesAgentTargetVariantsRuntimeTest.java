package ai.operativus.agentmanager.integration.schedules;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import ai.operativus.agentmanager.integration.support.RecordingAgentOperations;
import ai.operativus.agentmanager.integration.support.RecordingAgentOperationsConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Domain Responsibility: Pin {@code ScheduleExecutionPoller.executeAndPersist} contract
 *   for {@code targetType=AGENT} dispatches. Three configuration knobs on the AGENT
 *   path get passed to {@code AgentOperations.run(...)} — verify each:
 *   <ol>
 *     <li>{@code resumeSessionId} (null vs set) → 4th positional arg to
 *         {@code AgentOperations.run}</li>
 *     <li>{@code contextualPrompt} (null vs set) → 2nd positional arg
 *         ({@code userInput}); falls back to
 *         {@code "Executing scheduled task: " + schedule.getName()}</li>
 *     <li>{@code targetId} → 1st positional arg ({@code agentId})</li>
 *   </ol>
 *   {@link SchedulesRuntimeTest#pollerFiresActiveSchedule_dispatchesAsync_stampsTerminalCompletedAfterAgentRun}
 *   covers the dispatch happy path but doesn't assert these argument-positional
 *   contracts; a future refactor that swaps arg positions on
 *   {@code AgentOperations.run} would silently mis-route the schedule's session /
 *   instruction without tripping the existing tests.
 *
 *   Fixture: uses {@link RecordingAgentOperations} (the existing canonical stub)
 *   via {@link RecordingAgentOperationsConfig} to capture call args without
 *   invoking the real ChatClient.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        RecordingAgentOperationsConfig.class})
public class SchedulesAgentTargetVariantsRuntimeTest extends BaseIntegrationTest {

    @Autowired private RecordingAgentOperations recordingAgentOps;

    @BeforeEach
    void resetBeforeTest() {
        recordingAgentOps.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P2.2-1 — resumeSessionId=null → 4th positional arg to AgentOperations.run is null.
    // The agent path does NOT autocreate a session for AGENT (autocreate is WORKFLOW-only
    // per the poller's executeAndPersist body); AgentOperations.run owns the null-session
    // semantic.
    @Test
    void agentTarget_resumeSessionIdNull_passesNullSessionToAgentOperations() {
        HttpHeaders auth = adminAuth("variant-null-session");
        String agentId = seedAgent("variant-null-session-agent");
        String scheduleId = seedScheduleViaJdbc("variant-null-session-sched", agentId,
                /*resumeSessionId=*/null, /*contextualPrompt=*/null);

        rest.exchange(url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        // Select THIS test's dispatch by its unique agentId rather than asserting a
        // global calls.size()==1 — a background poller tick or a prior test's in-flight
        // async dispatch landing in the shared recorder would otherwise flake the count.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recordingAgentOps.calls.stream().anyMatch(c -> agentId.equals(c.agentId())));

        RecordingAgentOperations.Call call = recordingAgentOps.calls.stream()
                .filter(c -> agentId.equals(c.agentId())).findFirst().orElseThrow();
        assertNull(call.sessionId(),
                "AGENT target with null resumeSessionId must pass null as 4th positional arg — "
                        + "AgentOperations.run owns the fresh-session semantic; the poller does NOT autocreate "
                        + "for AGENT (autocreation is WORKFLOW-only).");
    }

    // P2.2-2 — resumeSessionId="my-session" → 4th positional arg matches verbatim.
    // This is the resume-existing-session contract that operators use to thread agent
    // state across scheduled fires.
    @Test
    void agentTarget_resumeSessionIdSet_passesSessionIdToAgentOperations() {
        HttpHeaders auth = adminAuth("variant-resume-session");
        String agentId = seedAgent("variant-resume-agent");
        String resumeSessionId = "session-resume-" + UUID.randomUUID();
        seedSession(resumeSessionId, agentId);

        String scheduleId = seedScheduleViaJdbc("variant-resume-sched", agentId,
                resumeSessionId, /*contextualPrompt=*/null);

        rest.exchange(url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recordingAgentOps.calls.stream().anyMatch(c -> agentId.equals(c.agentId())));

        RecordingAgentOperations.Call call = recordingAgentOps.calls.stream()
                .filter(c -> agentId.equals(c.agentId())).findFirst().orElseThrow();
        assertEquals(resumeSessionId, call.sessionId(),
                "AGENT target with resumeSessionId must pass it verbatim to AgentOperations.run — "
                        + "operators rely on this to thread agent state across scheduled fires");
    }

    // P2.2-3 — contextualPrompt → 2nd positional arg (instruction / userInput). Operators
    // set this to customize the LLM input per schedule (e.g. "Summarize yesterday's metrics").
    @Test
    void agentTarget_contextualPromptSet_passedAsInstructionToAgentOperations() {
        HttpHeaders auth = adminAuth("variant-prompt");
        String agentId = seedAgent("variant-prompt-agent");
        String customPrompt = "Summarize yesterday's metrics and email the result to ops.";
        String scheduleId = seedScheduleViaJdbc("variant-prompt-sched", agentId,
                /*resumeSessionId=*/null, customPrompt);

        rest.exchange(url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recordingAgentOps.calls.stream().anyMatch(c -> agentId.equals(c.agentId())));

        RecordingAgentOperations.Call call = recordingAgentOps.calls.stream()
                .filter(c -> agentId.equals(c.agentId())).findFirst().orElseThrow();
        assertEquals(customPrompt, call.input(),
                "contextualPrompt must be passed verbatim as the agent's userInput");
    }

    // P2.2-4 — contextualPrompt=null/blank → fallback to "Executing scheduled task: <name>"
    // (poller's executeAndPersist line ~166). Pin the default-instruction shape so a
    // future refactor doesn't silently drop the schedule name from agent observability.
    @Test
    void agentTarget_contextualPromptNullOrBlank_fallsBackToDefaultInstructionWithScheduleName() {
        HttpHeaders auth = adminAuth("variant-default-prompt");
        String agentId = seedAgent("variant-default-agent");
        String scheduleName = "daily-metrics-roll-up";
        String scheduleId = seedScheduleViaJdbcWithName("variant-default-sched", scheduleName,
                agentId, null, null);

        rest.exchange(url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recordingAgentOps.calls.stream().anyMatch(c -> agentId.equals(c.agentId())));

        RecordingAgentOperations.Call call = recordingAgentOps.calls.stream()
                .filter(c -> agentId.equals(c.agentId())).findFirst().orElseThrow();
        assertAll("default-instruction fallback includes schedule name",
                () -> assertNotNull(call.input(), "instruction must never be null"),
                () -> assertEquals("Executing scheduled task: " + scheduleName, call.input(),
                        "fallback shape pinned at ScheduleExecutionPoller.triggerScheduleExecution line ~166"));
    }

    // P2.2-5 — targetId → 1st positional arg (agentId). Cross-check: the recorded call's
    // agentId field must match the schedule's target_id. Catches a future refactor that
    // swaps arg positions on AgentOperations.run.
    @Test
    void agentTarget_targetId_passedAsAgentIdToAgentOperations() {
        HttpHeaders auth = adminAuth("variant-agent-id");
        String agentId = seedAgent("variant-agent-id-agent");
        String scheduleId = seedScheduleViaJdbc("variant-agent-id-sched", agentId,
                null, "go");

        rest.exchange(url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> recordingAgentOps.calls.stream().anyMatch(c -> agentId.equals(c.agentId())));

        RecordingAgentOperations.Call call = recordingAgentOps.calls.stream()
                .filter(c -> agentId.equals(c.agentId())).findFirst().orElseThrow();
        assertEquals(agentId, call.agentId(),
                "schedule.target_id must land in agentId (1st positional arg of AgentOperations.run)");
    }

    // ─── helpers ───

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-variant-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String seedAgent(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId, "Agent Variants " + label);
        return agentId;
    }

    private void seedSession(String sessionId, String agentId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, 'DEFAULT_SYSTEM_ORG', ?, now(), now())
                """, sessionId, "scheduler-user", agentId);
    }

    private String seedScheduleViaJdbc(String label, String targetId, String resumeSessionId, String contextualPrompt) {
        return seedScheduleViaJdbcWithName(label, "sched-" + label, targetId, resumeSessionId, contextualPrompt);
    }

    private String seedScheduleViaJdbcWithName(String label, String name, String targetId,
                                                 String resumeSessionId, String contextualPrompt) {
        String scheduleId = "schedule-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       resume_session_id, contextual_prompt,
                                       is_active, org_id, created_at, updated_at)
                VALUES (?, ?, ?, '0 0 12 * * *', 'AGENT', ?, ?, ?, true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """,
                scheduleId, name, "agent target variants " + label,
                targetId, resumeSessionId, contextualPrompt);
        return scheduleId;
    }
}
