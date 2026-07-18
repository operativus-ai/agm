package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Fills two remaining edge gaps in
 *   {@code POST /api/v1/a2a/peers/cancel-notify} coverage:
 *   <ul>
 *     <li><b>Null {@code reason}</b> — controller does
 *         {@code body.reason() != null ? body.reason() : ""} when composing the audit
 *         message. Sibling {@link PeerCancellationNotifyRuntimeTest} only tests with a
 *         non-null reason; the null branch is unpinned. A regression that NPE'd here
 *         would break peers that omit the field.</li>
 *     <li><b>Unauthenticated request</b> — the endpoint sits under {@code /api/v1/a2a/**}
 *         which the JWT filter protects. Anonymous POSTs MUST be rejected at 401,
 *         not silently audit-logged. Pinning here prevents a future change to the
 *         security config that accidentally adds this path to {@code publicPaths}.</li>
 *   </ul>
 *
 *   <p>Sibling coverage: {@link PeerCancellationNotifyRuntimeTest} pins the 204 happy
 *   path with reason + 400 missing/blank taskId; {@link A2aTaskLifecycleRuntimeTest}
 *   pins the same two 400 branches. This class pins what's left.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class A2aCancelNotifyEdgesRuntimeTest extends BaseIntegrationTest {

    // ─── CN1 — null reason -> 204 + audit message ends with bare prefix ────

    @Test
    void cancelNotify_nullReason_returns204_andAuditMessageHandlesNullGracefully() {
        HttpHeaders auth = authenticateAs("a2a-cn1-" + UUID.randomUUID().toString().substring(0, 8),
                "a2a-cn1@test.local", "pass-cn1-1234", List.of("ROLE_USER"));
        String taskId = "task-cn1-" + UUID.randomUUID();

        // Explicit null reason — Jackson maps the missing field to a null
        // record component.
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("reason", null);

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/a2a/peers/cancel-notify"), HttpMethod.POST,
                new HttpEntity<>(body, auth), Void.class);

        String message = jdbc.queryForObject(
                "SELECT message FROM a2a_task_events WHERE task_id = ?", String.class, taskId);
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?", Integer.class, taskId);

        assertAll("null reason is accepted and audited without NPE",
                () -> assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                        "null reason must NOT 400 — controller's ternary substitutes empty string"),
                () -> assertEquals(1, rowCount.intValue(),
                        "exactly one audit row must still land"),
                () -> assertNotNull(message),
                () -> assertEquals("notify-received: ", message,
                        "message MUST be the prefix followed by an empty string — pinning the "
                                + "exact shape catches a regression that either NPE'd, added "
                                + "'null' literal text, or dropped the prefix entirely"));
    }

    // ─── CN2 — omitted reason field behaves like null reason ───────────────

    @Test
    void cancelNotify_omittedReasonField_returns204_andAuditMessageHandlesNullGracefully() {
        HttpHeaders auth = authenticateAs("a2a-cn2-" + UUID.randomUUID().toString().substring(0, 8),
                "a2a-cn2@test.local", "pass-cn2-1234", List.of("ROLE_USER"));
        String taskId = "task-cn2-" + UUID.randomUUID();

        // Field entirely absent from the JSON — proves Jackson's missing-field handling
        // produces the same outcome as explicit null.
        Map<String, Object> body = Map.of("taskId", taskId);

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/a2a/peers/cancel-notify"), HttpMethod.POST,
                new HttpEntity<>(body, auth), Void.class);

        String message = jdbc.queryForObject(
                "SELECT message FROM a2a_task_events WHERE task_id = ?", String.class, taskId);

        assertAll("omitted reason field equivalent to explicit null",
                () -> assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode()),
                () -> assertEquals("notify-received: ", message));
    }

    // ─── CN3 — unauthenticated POST rejected at JWT filter (401) ────────────

    @Test
    void cancelNotify_unauthenticated_returns401_andNoAuditRow() {
        String taskId = "task-cn3-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("taskId", taskId, "reason", "from-anon");

        // No auth header — request hits the JWT filter at /api/v1/a2a/** before any
        // controller method runs.
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/a2a/peers/cancel-notify"), HttpMethod.POST,
                new HttpEntity<>(body, HttpHeaders.EMPTY), String.class);

        Integer auditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?",
                Integer.class, taskId);

        assertAll("/peers/cancel-notify requires authentication",
                () -> assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                        "anonymous POST must be rejected at the JWT filter; got "
                                + resp.getStatusCode() + ". A 204 here means /api/v1/a2a/** "
                                + "was accidentally added to SecurityConfig publicPaths — "
                                + "anyone on the internet could spray cancel notifies."),
                () -> assertEquals(0, auditRows.intValue(),
                        "no audit row may be written for an anonymous request — the security "
                                + "filter must short-circuit before the controller runs"));
    }
}
