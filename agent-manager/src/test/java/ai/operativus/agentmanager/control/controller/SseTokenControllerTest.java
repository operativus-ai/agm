package ai.operativus.agentmanager.control.controller;

import ai.operativus.agentmanager.control.repository.RunRepository;
import ai.operativus.agentmanager.control.security.SseTokenClaim;
import ai.operativus.agentmanager.control.security.SseTokenStore;
import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.registry.RunOperations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SseTokenControllerTest {

    private static final long TTL_SECONDS = 60L;

    @Mock private RunRepository runRepository;
    @Mock private SseTokenStore tokenStore;

    private MeterRegistry meterRegistry;
    private SseTokenController controller;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        controller = new SseTokenController(runRepository, tokenStore, meterRegistry, TTL_SECONDS);
    }

    @Test
    void issueToken_HappyPath_ReturnsTokenAndStoresClaim() {
        UUID userId = UUID.randomUUID();
        UserDetailsImpl principal = new UserDetailsImpl(
                userId, "alice", "alice@x.test", "org-1", false, "secret",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        AgentRun run = new AgentRun();
        run.setId("run-A");
        run.setOrgId("org-1");
        ((RunOperations) runRepository).getClass();
        when(((RunOperations) runRepository).findById("run-A")).thenReturn(Optional.of(run));

        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        var response = controller.issueToken("run-A", auth);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().expiresAt()).isNotNull();
        assertThat(counter("ok")).isEqualTo(1.0);
        assertThat(counter("failed")).isEqualTo(0.0);

        ArgumentCaptor<SseTokenClaim> claimCaptor = ArgumentCaptor.forClass(SseTokenClaim.class);
        verify(tokenStore).store(org.mockito.ArgumentMatchers.eq(response.getBody().token()),
                claimCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(TTL_SECONDS));
        SseTokenClaim claim = claimCaptor.getValue();
        assertThat(claim.runId()).isEqualTo("run-A");
        assertThat(claim.userId()).isEqualTo(userId.toString());
        assertThat(claim.orgId()).isEqualTo("org-1");
        assertThat(claim.authorities()).containsExactly("ROLE_USER");
    }

    @Test
    void issueToken_RunNotFound_Returns404AndIncrementsFailed() {
        UserDetailsImpl principal = new UserDetailsImpl(
                UUID.randomUUID(), "alice", "alice@x.test", "org-1", false, "secret",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(((RunOperations) runRepository).findById("missing")).thenReturn(Optional.empty());

        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        var response = controller.issueToken("missing", auth);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(counter("failed")).isEqualTo(1.0);
        assertThat(counter("ok")).isEqualTo(0.0);
    }

    @Test
    void issueToken_WrongOrg_Returns404NotForbidden() {
        // Returning 403 would leak run existence to other tenants. 404 is the intentional
        // envelope here — same as the rest of the runs read surface.
        UserDetailsImpl principal = new UserDetailsImpl(
                UUID.randomUUID(), "alice", "alice@x.test", "org-1", false, "secret",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        AgentRun run = new AgentRun();
        run.setId("run-other");
        run.setOrgId("org-2");
        when(((RunOperations) runRepository).findById("run-other")).thenReturn(Optional.of(run));

        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        var response = controller.issueToken("run-other", auth);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    void issueToken_NullAuthentication_Returns401() {
        var response = controller.issueToken("run-A", null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    private double counter(String outcome) {
        return meterRegistry.find("agm.sse.token.issued")
                .tag("outcome", outcome).counter().count();
    }
}
