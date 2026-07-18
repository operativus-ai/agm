package ai.operativus.agentmanager.control.security;

import java.util.Optional;

/**
 * Domain Responsibility: Storage contract for SSE short-lived tokens. Three implementations
 *   are selected at boot time via {@code agm.sse.token-store}:
 *   <ul>
 *     <li>{@link CaffeineSseTokenStore} — dev/local default; in-process Caffeine cache.</li>
 *     <li>{@link RedisSseTokenStore} — production preferred; cluster-shared via Redis SETEX.</li>
 *     <li>{@link PostgresSseTokenStore} — cluster fallback when Redis is unavailable.</li>
 *   </ul>
 * State: Stateless contract; backing storage is implementation-private.
 *
 * <p><b>Atomicity contract:</b> {@link #validateAndConsume(String, String)} must be atomic.
 * A successful return removes the token from storage in the same operation that read it; a
 * concurrent second call MUST observe the token as already consumed. Implementations enforce
 * this via {@code asMap().remove()} (Caffeine), Lua {@code GET + DEL} (Redis), or
 * {@code DELETE … RETURNING} (Postgres). This is the single-use guarantee that prevents
 * replay of an intercepted token.
 */
public interface SseTokenStore {

    /**
     * Persists a freshly-issued token with the given claim and TTL. Implementations must
     * honor the TTL even if the JVM dies mid-flight (Caffeine's loss is acceptable; Redis
     * and Postgres survive). Replacing an existing entry under the same {@code token} key
     * is undefined behavior — UUID v4 collisions are astronomically unlikely.
     */
    void store(String token, SseTokenClaim claim, long ttlSeconds);

    /**
     * Atomically validates the token and removes it from storage. Returns the stored claim
     * iff (a) the token exists, (b) {@code claim.runId().equals(expectedRunId)}, and (c)
     * {@code claim.expiresAt()} is in the future. Otherwise returns {@code Optional.empty()}.
     *
     * <p><b>Mismatched-run handling:</b> if the token exists but the runId does not match,
     * the token is NOT consumed — the legitimate request to the correct run can still use
     * it within its 60s window. The mismatch is reported by the caller as
     * {@code outcome=mismatched_run}; subsequent calls with the right runId still succeed.
     */
    Optional<SseTokenClaim> validateAndConsume(String token, String expectedRunId);

    /**
     * Read-only inspection without consuming. Used only by tests; production code paths must
     * not bypass the consume step. Returns the stored claim if present and not expired.
     */
    Optional<SseTokenClaim> peek(String token);
}
