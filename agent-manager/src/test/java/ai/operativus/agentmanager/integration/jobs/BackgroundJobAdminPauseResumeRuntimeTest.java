package ai.operativus.agentmanager.integration.jobs;

import ai.operativus.agentmanager.control.service.queue.JobQueueAdminState;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins the admin pause / resume / state surface for the
 *   persistent background job queue. Before this PR, {@link JobQueueAdminState#setPaused}
 *   had no REST caller — operators had no way to pause the queue without writing to
 *   {@code app_settings.job_queue.paused} directly. The startup log even pointed at an
 *   endpoint that did not exist:
 *   <pre>
 *     "Call POST /api/v1/observability/background-jobs/resume to re-enable."
 *   </pre>
 *   This class pins the wire shape of the three new endpoints + their auth gating +
 *   the persistence round-trip through {@code app_settings}.
 *
 *   <p>The state mutation is restored to the queue-active baseline in {@code @AfterEach}
 *   so paused state does not leak into other test classes that share the same Spring
 *   application context.
 *
 * State: Stateless (per-test reset via {@link #resetPauseState()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class BackgroundJobAdminPauseResumeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Boolean>> BOOL_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private JobQueueAdminState adminState;

    @AfterEach
    void resetPauseState() {
        // Guarantee the queue ends each test active so subsequent test classes
        // (e.g. JobQueueRuntimeTest) don't observe a leaked pause.
        adminState.setPaused(false);
        // Also wipe the system_audits rows our tests generate so the row-count
        // assertions in P6 don't compound across iterations.
        jdbc.update("DELETE FROM system_audits WHERE action IN ('JOB_QUEUE_PAUSE', 'JOB_QUEUE_RESUME')");
    }

    // ─── P6 — pause + resume each write a system_audits row ─────────────────

    @Test
    void pauseAndResume_writeSystemAuditRows_withActionAndPathTags() {
        HttpHeaders auth = adminHeaders("p6");

        rest.exchange(url("/api/v1/observability/background-jobs/pause"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);
        rest.exchange(url("/api/v1/observability/background-jobs/resume"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);

        Integer pauseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND request_path = ?",
                Integer.class, "JOB_QUEUE_PAUSE",
                "/api/v1/observability/background-jobs/pause");
        Integer resumeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = ? AND request_path = ?",
                Integer.class, "JOB_QUEUE_RESUME",
                "/api/v1/observability/background-jobs/resume");
        Integer pauseStatus = jdbc.queryForObject(
                "SELECT response_status FROM system_audits WHERE action = ? ORDER BY created_at DESC LIMIT 1",
                Integer.class, "JOB_QUEUE_PAUSE");

        assertAll("admin pause/resume produce a system_audits trail",
                () -> assertEquals(1, pauseRows.intValue(),
                        "POST /pause must write exactly one JOB_QUEUE_PAUSE row — without it, "
                                + "operators have no record of who paused the queue, when, "
                                + "or under what call. Failure-to-audit on a state-mutating "
                                + "admin action is the contract under test here."),
                () -> assertEquals(1, resumeRows.intValue(),
                        "POST /resume must write exactly one JOB_QUEUE_RESUME row"),
                () -> assertEquals(204, pauseStatus.intValue(),
                        "response_status must record the actual HTTP outcome"));
    }

    // ─── P1 — GET /pause-state returns {paused: false} when queue is active ────

    @Test
    void pauseState_default_returnsPausedFalse() {
        HttpHeaders auth = adminHeaders("p1");

        ResponseEntity<Map<String, Boolean>> resp = rest.exchange(
                url("/api/v1/observability/background-jobs/pause-state"),
                HttpMethod.GET, new HttpEntity<>(auth), BOOL_MAP);

        assertAll("default state read",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode()),
                () -> assertEquals(false, resp.getBody().get("paused"),
                        "queue must default to active — JobQueueAdminState.init reads "
                                + "app_settings.job_queue.paused which is absent in a fresh "
                                + "Testcontainers DB"));
    }

    // ─── P2 — POST /pause flips state + persists to app_settings ────────────

    @Test
    void pause_returns204_andStateFlipsToTrue_andSettingPersists() {
        HttpHeaders auth = adminHeaders("p2");

        ResponseEntity<Void> pause = rest.exchange(
                url("/api/v1/observability/background-jobs/pause"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);

        ResponseEntity<Map<String, Boolean>> state = rest.exchange(
                url("/api/v1/observability/background-jobs/pause-state"),
                HttpMethod.GET, new HttpEntity<>(auth), BOOL_MAP);

        String persistedSetting = jdbc.queryForObject(
                "SELECT setting_value FROM app_settings WHERE setting_key = ?",
                String.class, "job_queue.paused");

        assertAll("POST /pause flips + persists",
                () -> assertEquals(HttpStatus.NO_CONTENT, pause.getStatusCode(),
                        "pause must return 204 No Content"),
                () -> assertEquals(true, state.getBody().get("paused"),
                        "subsequent GET /pause-state must read 'true' — proves the in-memory "
                                + "AtomicBoolean cache was updated"),
                () -> assertEquals("true", persistedSetting,
                        "global_settings row MUST carry the persisted flag — a JVM restart "
                                + "must surface the same state via JobQueueAdminState.init"),
                () -> assertEquals(true, adminState.isPaused(),
                        "the singleton bean's flag must match the persisted value"));
    }

    // ─── P3 — POST /pause is idempotent ─────────────────────────────────────

    @Test
    void pause_twice_isIdempotentNoOp() {
        HttpHeaders auth = adminHeaders("p3");

        ResponseEntity<Void> first = rest.exchange(
                url("/api/v1/observability/background-jobs/pause"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);
        ResponseEntity<Void> second = rest.exchange(
                url("/api/v1/observability/background-jobs/pause"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);

        assertAll("idempotent pause",
                () -> assertEquals(HttpStatus.NO_CONTENT, first.getStatusCode()),
                () -> assertEquals(HttpStatus.NO_CONTENT, second.getStatusCode(),
                        "second pause must NOT 409 — operators may safely retry the call"),
                () -> assertEquals(true, adminState.isPaused()));
    }

    // ─── P4 — POST /resume after pause flips state back + persists ─────────

    @Test
    void resume_afterPause_returns204_andStateFlipsToFalse_andSettingPersists() {
        HttpHeaders auth = adminHeaders("p4");
        adminState.setPaused(true);  // setup: assume queue was paused already

        ResponseEntity<Void> resume = rest.exchange(
                url("/api/v1/observability/background-jobs/resume"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);

        String persisted = jdbc.queryForObject(
                "SELECT setting_value FROM app_settings WHERE setting_key = ?",
                String.class, "job_queue.paused");

        assertAll("POST /resume flips back to active + persists",
                () -> assertEquals(HttpStatus.NO_CONTENT, resume.getStatusCode()),
                () -> assertEquals(false, adminState.isPaused(),
                        "singleton flag must flip back to active"),
                () -> assertEquals("false", persisted,
                        "persisted row must reflect the resumed state"));
    }

    // ─── P5 — auth gating: anonymous + non-admin both rejected ──────────────

    @Test
    void pause_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/observability/background-jobs/pause"),
                HttpMethod.POST, new HttpEntity<>(HttpHeaders.EMPTY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "anonymous requests must be rejected at the JWT filter; got "
                        + resp.getStatusCode());
        // Bean state must NOT have flipped from the rejected call.
        assertEquals(false, adminState.isPaused());
    }

    @Test
    void pause_nonAdmin_returns403() {
        HttpHeaders userAuth = authenticateAs("p5-user", "p5-user@test.local",
                "pwd-p5-1234", List.of("ROLE_USER"));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/observability/background-jobs/pause"),
                HttpMethod.POST, new HttpEntity<>(userAuth), String.class);

        assertAll("non-admin caller rejected by @PreAuthorize",
                () -> assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                        "ROLE_USER must be rejected at the @PreAuthorize gate, not the JWT "
                                + "filter; got " + resp.getStatusCode()),
                () -> assertEquals(false, adminState.isPaused(),
                        "rejected call must NOT mutate state"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders adminHeaders(String label) {
        String username = "p" + label + "-admin-" + Long.toHexString(System.nanoTime());
        return authenticateAs(username, username + "@test.local", "pwd-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
