package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins lifecycle-edge behavior on
 *   {@code DELETE /api/v1/a2a/tasks/{taskId}} and
 *   {@code POST /api/v1/a2a/peers/cancel-notify}. The sibling A2aMeshRuntimeTest covers
 *   the unknown-task path (404) and PeerCancellationNotifyRuntimeTest covers the
 *   happy-path inbound notify; this class fills the idempotency and conflation gaps:
 *
 *     1. cancelTask is idempotent for unknown ids — repeated DELETEs are safe (each 404).
 *     2. cancel-notify with a missing taskId returns 400 and writes NO audit row
 *        (the sibling test asserts the 400 only — this also asserts no row leaked into
 *        a2a_task_events).
 *     3. cancel-notify with a blank taskId is treated identically to missing — 400 + no
 *        row. Confirms the {@code StringUtils.hasText} guard catches empty strings.
 *
 *   Submit-then-cancel-after-completion is intentionally NOT covered here because it
 *   requires either a real FakeChatModel-driven task or fragile sleep-based assertions;
 *   that scenario is tracked separately.
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aTaskLifecycleRuntimeTest extends BaseIntegrationTest {

    @BeforeEach
    void resetState() {
        jdbc.update("TRUNCATE TABLE a2a_task_events");
    }

    @Test
    void cancelTask_unknownId_isIdempotentOver_repeatedDeletes() {
        HttpHeaders auth = userHeaders("a2a-lifecycle-idempotent");
        String unknownId = "task-unknown-" + UUID.randomUUID();

        ResponseEntity<Void> first = rest.exchange(
                url("/api/v1/a2a/tasks/" + unknownId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        ResponseEntity<Void> second = rest.exchange(
                url("/api/v1/a2a/tasks/" + unknownId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        ResponseEntity<Void> third = rest.exchange(
                url("/api/v1/a2a/tasks/" + unknownId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertAll("cancelTask must be safely idempotent — repeated unknown-id DELETEs all 404",
                () -> assertEquals(404, first.getStatusCode().value()),
                () -> assertEquals(404, second.getStatusCode().value()),
                () -> assertEquals(404, third.getStatusCode().value()));
    }

    @Test
    void cancelNotify_missingTaskId_returns400_andDoesNotWriteAuditRow() {
        HttpHeaders auth = userHeaders("a2a-lifecycle-notify-missing");
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "remote-side-aborted");
        // intentionally no taskId

        ResponseEntity<Void> notify = rest.exchange(
                url("/api/v1/a2a/peers/cancel-notify"), HttpMethod.POST,
                new HttpEntity<>(body, auth), Void.class);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE message LIKE 'notify-received%'",
                Integer.class);

        assertAll("missing taskId → 400 and no audit leak",
                () -> assertEquals(400, notify.getStatusCode().value()),
                () -> assertEquals(0, rows,
                        "no a2a_task_events row should be appended when the payload is rejected — "
                                + "otherwise audit log includes 400s and breaks operator triage"));
    }

    @Test
    void cancelNotify_blankTaskId_returns400_andDoesNotWriteAuditRow() {
        HttpHeaders auth = userHeaders("a2a-lifecycle-notify-blank");
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", "");
        body.put("reason", "remote-side-aborted");

        ResponseEntity<Void> notify = rest.exchange(
                url("/api/v1/a2a/peers/cancel-notify"), HttpMethod.POST,
                new HttpEntity<>(body, auth), Void.class);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE message LIKE 'notify-received%'",
                Integer.class);

        assertAll("blank taskId → treated as missing → 400 and no audit leak",
                () -> assertEquals(400, notify.getStatusCode().value()),
                () -> assertEquals(0, rows));
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-a2a-lifecycle", List.of("ROLE_USER"));
    }
}
