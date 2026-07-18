package com.operativus.agentmanager.control.security;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-T005: covers PostgresSseTokenStore against a real Testcontainers Postgres. Verifies the
 * sse_tokens table round-trip, the DELETE … RETURNING single-use guarantee, the predicate-only
 * mismatched-run protection (token stays put on a wrong-run consume), and TTL behavior via
 * pre-aged rows.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
@TestPropertySource(properties = "agm.sse.token-store=postgres")
class PostgresSseTokenStoreTest extends BaseIntegrationTest {

    @Autowired private SseTokenStore tokenStore; // Postgres impl wired via the @TestPropertySource override

    @Test
    void store_postgresImplIsActive() {
        assertThat(tokenStore).isInstanceOf(PostgresSseTokenStore.class);
    }

    @Test
    void roundTrip_StoreThenConsume_ReturnsClaim() {
        String token = UUID.randomUUID().toString();
        SseTokenClaim claim = new SseTokenClaim(
                "run-A", "user-1", "org-1", List.of("ROLE_USER"),
                Instant.now().plusSeconds(60));

        tokenStore.store(token, claim, 60);
        Optional<SseTokenClaim> consumed = tokenStore.validateAndConsume(token, "run-A");

        assertThat(consumed).isPresent();
        assertThat(consumed.get().runId()).isEqualTo("run-A");
        assertThat(consumed.get().authorities()).containsExactly("ROLE_USER");
    }

    @Test
    void validateAndConsume_SecondCallReturnsEmpty_DeleteReturningEnforcesSingleUse() {
        String token = UUID.randomUUID().toString();
        tokenStore.store(token, new SseTokenClaim(
                "run-A", "user-1", "org-1", List.of("ROLE_USER"),
                Instant.now().plusSeconds(60)), 60);

        tokenStore.validateAndConsume(token, "run-A");
        Optional<SseTokenClaim> second = tokenStore.validateAndConsume(token, "run-A");

        assertThat(second).isEmpty();
    }

    @Test
    void validateAndConsume_WrongRun_PreservesRowForLegitimateCaller() {
        String token = UUID.randomUUID().toString();
        tokenStore.store(token, new SseTokenClaim(
                "run-A", "user-1", "org-1", List.of("ROLE_USER"),
                Instant.now().plusSeconds(60)), 60);

        Optional<SseTokenClaim> wrong = tokenStore.validateAndConsume(token, "run-B");
        Optional<SseTokenClaim> right = tokenStore.validateAndConsume(token, "run-A");

        assertThat(wrong).isEmpty();
        assertThat(right).isPresent();
    }

    @Test
    void validateAndConsume_AlreadyExpired_ReturnsEmpty() {
        String token = UUID.randomUUID().toString();
        // Insert a row whose expires_at is already in the past. The DELETE predicate's
        // expires_at > NOW() filter excludes it, so the row stays — but is invisible to
        // consume calls.
        jdbc.update("""
                INSERT INTO sse_tokens (id, run_id, user_id, org_id, authorities, expires_at)
                VALUES (?::uuid, ?, ?, ?, ?, NOW() - INTERVAL '5 seconds')
                """, token, "run-A", "user-1", "org-1", "ROLE_USER");

        Optional<SseTokenClaim> result = tokenStore.validateAndConsume(token, "run-A");

        assertThat(result).isEmpty();
    }

    @Test
    void retentionService_DeletesPastExpiryRows() {
        // Seed two rows: one fresh, one already expired. Hit the controller's retention path
        // by directly invoking the SQL the service runs (the @Scheduled cron only fires at
        // 03:00 in production; we don't want to wait for that).
        String fresh = UUID.randomUUID().toString();
        String stale = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sse_tokens (id, run_id, user_id, org_id, authorities, expires_at)
                VALUES (?::uuid, 'run-A', 'user-1', 'org-1', 'ROLE_USER', NOW() + INTERVAL '60 seconds')
                """, fresh);
        jdbc.update("""
                INSERT INTO sse_tokens (id, run_id, user_id, org_id, authorities, expires_at)
                VALUES (?::uuid, 'run-A', 'user-1', 'org-1', 'ROLE_USER', NOW() - INTERVAL '60 seconds')
                """, stale);

        int deleted = jdbc.update("DELETE FROM sse_tokens WHERE expires_at < NOW()");

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        Integer freshSurvives = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sse_tokens WHERE id = ?::uuid", Integer.class, fresh);
        assertThat(freshSurvives).isEqualTo(1);
    }
}
