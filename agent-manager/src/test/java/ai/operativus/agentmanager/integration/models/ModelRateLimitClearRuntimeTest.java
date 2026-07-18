package ai.operativus.agentmanager.integration.models;

import ai.operativus.agentmanager.compute.security.ModelRateLimitGuard;
import ai.operativus.agentmanager.core.exception.RateLimitExceededException;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain Responsibility: Pin the §6 M-12 Phase 4 wire shape — {@code DELETE
 * /api/models/{id}/rate-limit} — and the Anti-pattern A1 fix (the in-memory
 * {@link ModelRateLimitGuard} is invalidated inside the same {@code @Transactional}
 * boundary that nulls the column).
 *
 * <p>The unit-level invalidation behaviour is pinned by
 * {@code ModelRateLimitGuardTest.invalidate_*}. What is NOT covered without this
 * runtime test is the controller→service→guard wiring: a refactor that drops the
 * {@code guard.invalidate(id)} call in {@code ModelService.clearRateLimit} would
 * leave the unit tests green but leave the cached limiter stale in production.
 *
 * <p>Anti-pattern A2 (time-windowed flakiness) is mitigated by relying on T005's
 * invalidate hook — the test does not advance the wall clock; the post-clear
 * admit is deterministic because the guard's bookkeeping is fully reset.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ModelRateLimitClearRuntimeTest extends BaseIntegrationTest {

    @Autowired private ModelRateLimitGuard guard;

    private static final String MODEL_ID = "rate-limit-clear-fixture";

    @BeforeEach
    void resetState() {
        truncateDatabase();
        seedModel(MODEL_ID, /*rpm=*/ 3);
    }

    /** T010 — happy path: set rpm=3, exhaust, verify rejected; clear via DELETE; next call admits. */
    @Test
    void clearRateLimit_exhaustedThenCleared_admitsImmediately() {
        // Exhaust the per-model gate.
        guard.acquireOrThrow(MODEL_ID);
        guard.acquireOrThrow(MODEL_ID);
        guard.acquireOrThrow(MODEL_ID);
        assertThatThrownBy(() -> guard.acquireOrThrow(MODEL_ID))
                .as("4th call within the minute window must be rejected by the gate")
                .isInstanceOf(RateLimitExceededException.class);

        // Operator clears via DELETE.
        HttpHeaders adminAuth = authenticateAs(
                "rl-clear-admin", "rl-clear-admin@test.local", "pass-rlc-1234",
                List.of("ROLE_ADMIN"));
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/models/" + MODEL_ID + "/rate-limit"),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Column is null.
        Integer rpmAfter = jdbc.queryForObject(
                "SELECT rate_limit_rpm FROM models WHERE id = ?", Integer.class, MODEL_ID);
        assertThat(rpmAfter).as("DELETE nulls the rate_limit_rpm column").isNull();

        // Next acquire is a no-op (rpm null → guard returns early). No time-windowing needed
        // because the guard's cached limiter + appliedRpm bookkeeping was invalidated.
        for (int i = 0; i < 50; i++) {
            int attempt = i;
            assertThatNoException()
                    .as("post-clear admit #%d", attempt)
                    .isThrownBy(() -> guard.acquireOrThrow(MODEL_ID));
        }
    }

    /** T011 case 1 — ROLE_USER cannot clear another tenant's gate. Service-layer @PreAuthorize → 403. */
    @Test
    void clearRateLimit_roleUser_isForbidden403() {
        HttpHeaders userAuth = authenticateAs(
                "rl-clear-user", "rl-clear-user@test.local", "pass-rlu-1234",
                List.of("ROLE_USER"));
        ResponseEntity<String> resp = rest.exchange(
                url("/api/models/" + MODEL_ID + "/rate-limit"),
                HttpMethod.DELETE,
                new HttpEntity<>(null, userAuth),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** T011 case 2 — unknown id returns 404 with the resource-not-found problem type. */
    @Test
    void clearRateLimit_unknownId_returns404() {
        HttpHeaders adminAuth = authenticateAs(
                "rl-clear-admin-2", "rl-clear-admin-2@test.local", "pass-rl2-1234",
                List.of("ROLE_ADMIN"));
        ResponseEntity<String> resp = rest.exchange(
                url("/api/models/" + UUID.randomUUID() + "/rate-limit"),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody())
                .as("404 surfaces the standard problem-type")
                .contains("urn:problem-type:resource-not-found");
    }

    /** T011 case 3 — clear is idempotent: re-clear on already-null returns 204 (not 409 / 500). */
    @Test
    void clearRateLimit_alreadyCleared_isIdempotent204() {
        HttpHeaders adminAuth = authenticateAs(
                "rl-clear-admin-3", "rl-clear-admin-3@test.local", "pass-rl3-1234",
                List.of("ROLE_ADMIN"));

        // First clear.
        ResponseEntity<Void> firstResp = rest.exchange(
                url("/api/models/" + MODEL_ID + "/rate-limit"),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                Void.class);
        assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Second clear on the now-null column — must NOT throw, must return 204.
        ResponseEntity<Void> secondResp = rest.exchange(
                url("/api/models/" + MODEL_ID + "/rate-limit"),
                HttpMethod.DELETE,
                new HttpEntity<>(null, adminAuth),
                Void.class);
        assertThat(secondResp.getStatusCode())
                .as("idempotent clear — second DELETE on already-null column is 204, not 409 or 500")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void seedModel(String id, Integer rpm) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at, rate_limit_rpm)
                VALUES (?, ?, 'fake', 'fake-gpt', true, false, true, 'CHAT', now(), ?)
                ON CONFLICT (id) DO UPDATE SET rate_limit_rpm = EXCLUDED.rate_limit_rpm
                """,
                id, id, rpm);
    }
}
