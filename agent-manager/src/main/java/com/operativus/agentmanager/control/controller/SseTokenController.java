package com.operativus.agentmanager.control.controller;

import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.control.security.SseTokenClaim;
import com.operativus.agentmanager.control.security.SseTokenStore;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.registry.RunOperations;
import com.operativus.agentmanager.core.model.SseTokenResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain Responsibility: OBS-T005 — issues short-lived opaque tokens that authorize a single
 *   {@code GET /api/v1/runs/{runId}/events} SSE connection. Browser EventSource cannot send
 *   {@code Authorization} headers, so the frontend exchanges its JWT for a token here, then
 *   opens the stream with {@code ?token=<...>}.
 * State: Stateless controller; token state lives in {@link SseTokenStore}.
 *
 * <p>Tokens are single-use UUID v4 strings (cryptographic randomness via
 * {@code UUID.randomUUID()}'s {@code SecureRandom} seed) with a configurable TTL — default
 * 60 seconds (long enough for an EventSource open round-trip, short enough that a leaked
 * token is near-useless). Single-use and TTL together protect against replay.
 *
 * <p>Run authorization is verified before issuing: the caller must have access to {@code runId}
 * (404 otherwise — same envelope as the rest of the runs surface, not 403, to avoid revealing
 * existence). The token's stored authorities are the caller's JWT authorities; no escalation.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class SseTokenController {

    private final RunRepository runRepository;
    private final SseTokenStore tokenStore;
    private final long ttlSeconds;
    private final Counter issuedOkCounter;
    private final Counter issuedFailedCounter;

    public SseTokenController(
            RunRepository runRepository,
            SseTokenStore tokenStore,
            MeterRegistry meterRegistry,
            @Value("${agm.sse.token.ttl-seconds:60}") long ttlSeconds) {
        this.runRepository = runRepository;
        this.tokenStore = tokenStore;
        this.ttlSeconds = ttlSeconds;
        this.issuedOkCounter = Counter.builder("agm.sse.token.issued")
                .tag("outcome", "ok").register(meterRegistry);
        this.issuedFailedCounter = Counter.builder("agm.sse.token.issued")
                .tag("outcome", "failed").register(meterRegistry);
    }

    /**
     * @summary Issues a 60-second opaque SSE token for {@code runId}, bound to the caller's
     *     identity and authorities.
     * @logic
     * - Resolve the caller via the existing JWT-populated SecurityContext.
     * - Confirm the run exists and (if tenant-scoped) belongs to the caller's org. Anything
     *   else surfaces as 404 with no body.
     * - Mint a UUID, build an {@link SseTokenClaim} from the JWT principal + run id, persist
     *   to the {@link SseTokenStore} with TTL, and return {@link SseTokenResponse}.
     * - Increment the {@code agm.sse.token.issued{outcome=ok|failed}} counter.
     */
    @PostMapping("/{runId}/sse-token")
    public ResponseEntity<SseTokenResponse> issueToken(
            @PathVariable("runId") String runId,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl principal)) {
            issuedFailedCounter.increment();
            return ResponseEntity.status(401).build();
        }
        // RunRepository extends both JpaRepository and RunOperations, both expose findById —
        // pin the RunOperations view to disambiguate at compile time.
        RunOperations runOps = runRepository;
        Optional<AgentRun> existing = runOps.findById(runId);
        if (existing.isEmpty()) {
            issuedFailedCounter.increment();
            return ResponseEntity.notFound().build();
        }
        AgentRun run = existing.get();
        if (principal.getOrgId() != null && run.getOrgId() != null
                && !principal.getOrgId().equals(run.getOrgId())) {
            issuedFailedCounter.increment();
            return ResponseEntity.notFound().build();
        }

        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        List<String> authorityNames = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        SseTokenClaim claim = new SseTokenClaim(
                runId,
                principal.getId().toString(),
                principal.getOrgId(),
                authorityNames,
                expiresAt);

        tokenStore.store(token, claim, ttlSeconds);
        issuedOkCounter.increment();
        return ResponseEntity.ok(new SseTokenResponse(token, expiresAt));
    }
}
