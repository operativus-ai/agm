package com.operativus.agentmanager.control.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Domain Responsibility: Postgres-backed implementation of {@link SseTokenStore} for
 *   cluster deployments where Redis is unavailable. Uses the {@code sse_tokens} table
 *   (Liquibase changeset 039); cleanup is handled by {@code DataRetentionService}.
 * State: Stateless (table is the source of truth).
 *
 * <p><b>Single-use enforcement:</b> {@link #validateAndConsume} uses
 * {@code DELETE … RETURNING …} — a Postgres-specific atomic pattern where the row is
 * removed and its prior contents returned in one statement. Concurrent callers either get
 * the row or an empty result; only one wins.
 *
 * <p><b>Mismatched-run handling:</b> the DELETE predicate includes
 * {@code AND run_id = :expectedRunId}. A mismatched runId leaves the row untouched (no
 * RETURNING rows), and the legitimate caller can still consume.
 */
@Service
@ConditionalOnProperty(name = "agm.sse.token-store", havingValue = "postgres")
public class PostgresSseTokenStore implements SseTokenStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresSseTokenStore.class);

    private static final String AUTHORITIES_DELIM = ",";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public PostgresSseTokenStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    PostgresSseTokenStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public void store(String token, SseTokenClaim claim, long ttlSeconds) {
        jdbcTemplate.update("""
                INSERT INTO sse_tokens (id, run_id, user_id, org_id, authorities, expires_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                """,
                token,
                claim.runId(),
                claim.userId(),
                claim.orgId(),
                String.join(AUTHORITIES_DELIM, claim.authorities()),
                Timestamp.from(claim.expiresAt()));
    }

    @Override
    public Optional<SseTokenClaim> validateAndConsume(String token, String expectedRunId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    DELETE FROM sse_tokens
                     WHERE id = ?::uuid
                       AND run_id = ?
                       AND expires_at > NOW()
                    RETURNING run_id, user_id, org_id, authorities, expires_at
                    """, token, expectedRunId);
            if (rows.isEmpty()) return Optional.empty();
            Map<String, Object> row = rows.get(0);
            SseTokenClaim claim = mapRow(row);
            if (Instant.now(clock).isAfter(claim.expiresAt())) return Optional.empty();
            return Optional.of(claim);
        } catch (RuntimeException ex) {
            log.warn("PostgresSseTokenStore: validateAndConsume failed", ex);
            return Optional.empty();
        }
    }

    @Override
    public Optional<SseTokenClaim> peek(String token) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT run_id, user_id, org_id, authorities, expires_at
                      FROM sse_tokens
                     WHERE id = ?::uuid
                       AND expires_at > NOW()
                    """, token);
            return Optional.of(mapRow(row));
        } catch (EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }

    private static SseTokenClaim mapRow(Map<String, Object> row) {
        String authoritiesJoined = (String) row.get("authorities");
        List<String> authorities = authoritiesJoined == null || authoritiesJoined.isEmpty()
                ? List.of()
                : List.of(authoritiesJoined.split(AUTHORITIES_DELIM));
        Timestamp expiresAtTs = (Timestamp) row.get("expires_at");
        return new SseTokenClaim(
                (String) row.get("run_id"),
                (String) row.get("user_id"),
                (String) row.get("org_id"),
                authorities,
                expiresAtTs.toInstant());
    }
}
