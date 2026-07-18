package com.operativus.agentmanager.control.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain Responsibility: OBS-T005 — authenticates SSE event-stream requests bearing a
 *   {@code ?token=…} query parameter. Browser EventSource cannot send {@code Authorization}
 *   headers, so this filter consumes the short-lived opaque token issued by
 *   {@link com.operativus.agentmanager.control.controller.SseTokenController} and synthesizes
 *   an {@code Authentication} for the rest of the chain.
 * State: Stateless filter; token state lives in {@link SseTokenStore}.
 *
 * <p>Registered <b>before</b> {@code JwtAuthenticationFilter} in {@code SecurityConfig}, so an
 * SSE request without a valid {@code Authorization} header still reaches the protected
 * controller. Non-SSE requests (anything that doesn't match the path pattern) pass straight
 * through this filter.
 *
 * <p>Token validation outcomes are reported on {@code agm.sse.token.validated{outcome=…}}:
 * <ul>
 *   <li>{@code ok} — token consumed, authentication set</li>
 *   <li>{@code unknown} — token absent from store (or already consumed)</li>
 *   <li>{@code expired} — token's {@code expiresAt} is in the past (defense-in-depth; stores
 *       also enforce TTL)</li>
 *   <li>{@code mismatched_run} — token exists but is bound to a different runId</li>
 * </ul>
 */
@Component
public class SseTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SseTokenAuthFilter.class);

    private static final Pattern EVENTS_PATH = Pattern.compile("^/api/v1/runs/([^/]+)/events/?$");

    private final SseTokenStore tokenStore;
    private final Counter validatedOkCounter;
    private final Counter validatedExpiredCounter;
    private final Counter validatedUnknownCounter;
    private final Counter validatedMismatchedRunCounter;

    public SseTokenAuthFilter(SseTokenStore tokenStore, MeterRegistry meterRegistry) {
        this.tokenStore = tokenStore;
        this.validatedOkCounter = Counter.builder("agm.sse.token.validated")
                .tag("outcome", "ok").register(meterRegistry);
        this.validatedExpiredCounter = Counter.builder("agm.sse.token.validated")
                .tag("outcome", "expired").register(meterRegistry);
        this.validatedUnknownCounter = Counter.builder("agm.sse.token.validated")
                .tag("outcome", "unknown").register(meterRegistry);
        this.validatedMismatchedRunCounter = Counter.builder("agm.sse.token.validated")
                .tag("outcome", "mismatched_run").register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Matcher m = EVENTS_PATH.matcher(request.getRequestURI());
        if (!m.matches()) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getParameter("token");
        if (token == null || token.isBlank()) {
            // Not an SSE-token-bearing request; let JwtAuthenticationFilter try Bearer auth.
            chain.doFilter(request, response);
            return;
        }
        String runId = m.group(1);

        Optional<SseTokenClaim> consumed = tokenStore.validateAndConsume(token, runId);
        if (consumed.isPresent()) {
            SseTokenClaim claim = consumed.get();
            if (Instant.now().isAfter(claim.expiresAt())) {
                validatedExpiredCounter.increment();
                writeUnauthorized(response, "sse_token_expired");
                return;
            }
            List<SimpleGrantedAuthority> authorities = claim.authorities().stream()
                    .map(SimpleGrantedAuthority::new).toList();
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(claim.userId(), null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            validatedOkCounter.increment();
            chain.doFilter(request, response);
            return;
        }

        // validateAndConsume returned empty. Use peek (non-consuming) to disambiguate the
        // failure mode: still-present-but-mismatched-run vs absent vs expired.
        Optional<SseTokenClaim> peeked = tokenStore.peek(token);
        if (peeked.isEmpty()) {
            validatedUnknownCounter.increment();
            writeUnauthorized(response, "sse_token_invalid");
            return;
        }
        SseTokenClaim peekedClaim = peeked.get();
        if (Instant.now().isAfter(peekedClaim.expiresAt())) {
            validatedExpiredCounter.increment();
            writeUnauthorized(response, "sse_token_expired");
            return;
        }
        if (!peekedClaim.runId().equals(runId)) {
            validatedMismatchedRunCounter.increment();
            writeUnauthorized(response, "sse_token_run_mismatch");
            return;
        }
        // Theoretically unreachable: peek says it's good, but consume failed. Race with
        // another consumer. Treat as unknown.
        validatedUnknownCounter.increment();
        writeUnauthorized(response, "sse_token_invalid");
    }

    private static void writeUnauthorized(HttpServletResponse response, String error) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
