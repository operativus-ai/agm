package com.operativus.agentmanager.control.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SseTokenAuthFilterTest {

    @Mock private SseTokenStore tokenStore;
    @Mock private FilterChain chain;

    private MeterRegistry meterRegistry;
    private SseTokenAuthFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new SseTokenAuthFilter(tokenStore, meterRegistry);
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_NonSsePath_PassesThroughUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/run-A");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(tokenStore, never()).validateAndConsume(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilter_SsePathNoToken_PassesThroughForJwtFilterToHandle() throws Exception {
        // No ?token= parameter. Filter must defer to the next filter (JWT) without 401-ing,
        // because Bearer-auth-via-Authorization-header is still a valid path for non-browser
        // EventSource clients (curl, server-to-server).
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/run-A/events");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(tokenStore, never()).validateAndConsume(any(), any());
    }

    @Test
    void doFilter_HappyPath_AuthenticatesAndContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/run-A/events");
        request.setParameter("token", "tok-good");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SseTokenClaim claim = new SseTokenClaim(
                "run-A", "user-1", "org-1",
                List.of("ROLE_USER", "ROLE_OPERATOR"),
                Instant.now().plusSeconds(60));
        when(tokenStore.validateAndConsume("tok-good", "run-A")).thenReturn(Optional.of(claim));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("user-1");
        assertThat(auth.getAuthorities().stream().map(Object::toString))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_OPERATOR");
        assertThat(counter("ok")).isEqualTo(1.0);
    }

    @Test
    void doFilter_UnknownToken_Returns401AndIncrementsUnknown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/run-A/events");
        request.setParameter("token", "tok-missing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenStore.validateAndConsume("tok-missing", "run-A")).thenReturn(Optional.empty());
        when(tokenStore.peek("tok-missing")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("sse_token_invalid");
        assertThat(counter("unknown")).isEqualTo(1.0);
    }

    @Test
    void doFilter_WrongRunToken_Returns401MismatchedRunAndDoesNotConsume() throws Exception {
        // Token T was issued for run-B; client tries it on run-A. validateAndConsume returns
        // empty (predicate failed), and peek shows the token is still there bound to run-B.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/run-A/events");
        request.setParameter("token", "tok-wrong-run");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenStore.validateAndConsume("tok-wrong-run", "run-A")).thenReturn(Optional.empty());
        SseTokenClaim claim = new SseTokenClaim(
                "run-B", "user-1", "org-1", List.of("ROLE_USER"),
                Instant.now().plusSeconds(60));
        when(tokenStore.peek("tok-wrong-run")).thenReturn(Optional.of(claim));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("sse_token_run_mismatch");
        assertThat(counter("mismatched_run")).isEqualTo(1.0);
    }

    @Test
    void doFilter_ExpiredToken_Returns401Expired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/run-A/events");
        request.setParameter("token", "tok-expired");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // peek returns a claim whose expiresAt is in the past — store didn't catch it (e.g.
        // edge of TTL window), but the filter's defense-in-depth check rejects it.
        when(tokenStore.validateAndConsume("tok-expired", "run-A")).thenReturn(Optional.empty());
        SseTokenClaim claim = new SseTokenClaim(
                "run-A", "user-1", "org-1", List.of("ROLE_USER"),
                Instant.now().minusSeconds(1));
        when(tokenStore.peek("tok-expired")).thenReturn(Optional.of(claim));

        filter.doFilter(request, response, chain);

        // Note: peek itself filters past-expiry claims (Optional.empty), so this is a
        // contrived scenario — but the filter's wall-clock guard still has to handle a stale
        // claim that slipped through.
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void doFilter_ReusedTokenAfterConsume_Returns401Unknown() throws Exception {
        // First call: token consumed successfully (already covered by happy path test).
        // Second call: store no longer has the token. unknown counter increments.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs/run-A/events");
        request.setParameter("token", "tok-once");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenStore.validateAndConsume("tok-once", "run-A")).thenReturn(Optional.empty());
        when(tokenStore.peek("tok-once")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("sse_token_invalid");
        assertThat(counter("unknown")).isEqualTo(1.0);
        verify(chain, never()).doFilter(any(), any());
        // Filter never re-consumes after first failed attempt.
        verify(tokenStore, times(1)).validateAndConsume(eq("tok-once"), eq("run-A"));
    }

    private double counter(String outcome) {
        return meterRegistry.find("agm.sse.token.validated")
                .tag("outcome", outcome).counter().count();
    }
}
