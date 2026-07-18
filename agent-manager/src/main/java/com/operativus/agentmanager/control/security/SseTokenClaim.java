package com.operativus.agentmanager.control.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Persisted form of an issued SSE token. Carries the principal context
 *   needed by {@link SseTokenAuthFilter} to synthesize a Spring Security {@code Authentication}
 *   for an EventSource request that cannot send {@code Authorization} headers.
 * State: Immutable record.
 *
 * <p>Authorities are stored as {@code List<String>} (role names) rather than
 * {@code GrantedAuthority} objects so the same claim payload round-trips cleanly through any
 * of the three {@link SseTokenStore} implementations (Caffeine in-memory, Redis serialized,
 * Postgres TEXT column). The filter rehydrates them as {@code SimpleGrantedAuthority} on the
 * way back into {@code SecurityContextHolder}.
 *
 * <p>{@code expiresAt} is the wall-clock instant past which the token must be rejected. Stores
 * also enforce TTL on their own (Caffeine {@code expireAfterWrite}, Redis {@code SETEX},
 * Postgres index + retention sweep) — {@code expiresAt} is the in-claim defense-in-depth check
 * so a clock-skewed store cannot accidentally serve a logically-expired claim.
 */
public record SseTokenClaim(
        String runId,
        String userId,
        String orgId,
        List<String> authorities,
        Instant expiresAt
) implements Serializable {
}
