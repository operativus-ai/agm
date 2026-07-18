package ai.operativus.agentmanager.core.callback;

import ai.operativus.agentmanager.control.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pin the fallback chain on
 * {@link AgentContextHolder#getOrgId()} added by PR #927 (Bug #18).
 *
 * Pre-fix, {@code getOrgId()} returned only the ScopedValue binding — so any
 * background-worker or scheduler-spawned thread (where
 * {@code TenantContextFilter} doesn't run and the ScopedValue isn't bound)
 * saw {@code orgId=null} even when the {@code SecurityContext} had been
 * restored via {@code pendingAuthContexts}. Downstream code that relies on
 * tenant context — e.g. the strict-orgId branch of
 * {@code AgentRegistry.findById} — would then misroute or fail to resolve.
 *
 * Post-fix the resolution order is:
 *   1. {@code orgId} ScopedValue (request thread set by TenantContextFilter)
 *   2. {@code SecurityContextHolder.getContext().getAuthentication()} →
 *      {@code UserDetailsImpl.getOrgId()} (background work that has had
 *      SecurityContext restored)
 *   3. {@code null} (unauthenticated path or unknown principal type)
 *
 * State: Stateless (per-test SecurityContext cleared in @AfterEach).
 */
class AgentContextHolderGetOrgIdTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopedValueBound_returnsBoundValue_skipsSecurityContextFallback() {
        // ScopedValue takes precedence: even if the SecurityContext has a
        // UserDetailsImpl with a DIFFERENT orgId, the bound ScopedValue wins.
        // This pins the "request thread reads from filter-set ScopedValue" path.
        setSecurityContext(buildUserDetails("other-org-from-security-context"));

        String observed = ScopedValue.where(AgentContextHolder.orgId, "scoped-org")
                .call(AgentContextHolder::getOrgId);

        assertThat(observed)
                .as("ScopedValue binding must take precedence over SecurityContext fallback")
                .isEqualTo("scoped-org");
    }

    @Test
    void scopedValueUnbound_securityContextHasUserDetails_returnsItsOrgId() {
        // The Bug #18 / PR #927 fix path. Background worker thread: ScopedValue
        // not bound (TenantContextFilter didn't run), but SecurityContext has
        // been restored from pendingAuthContexts. getOrgId must read it.
        setSecurityContext(buildUserDetails("restored-org-from-pending-auth"));

        String observed = AgentContextHolder.getOrgId();

        assertThat(observed)
                .as("background thread with restored SecurityContext must yield the principal's orgId — pre-fix this returned null")
                .isEqualTo("restored-org-from-pending-auth");
    }

    @Test
    void scopedValueUnbound_noSecurityContext_returnsNull() {
        // No ScopedValue, no Authentication on the SecurityContext. The
        // pre-fix behavior IS still correct here — null is the right answer
        // when there's nothing to derive an orgId from.
        SecurityContextHolder.clearContext();

        String observed = AgentContextHolder.getOrgId();

        assertThat(observed).isNull();
    }

    @Test
    void scopedValueUnbound_unauthenticatedAuthentication_returnsNull() {
        // An Authentication present but with isAuthenticated()=false must NOT
        // satisfy the fallback. Otherwise an anonymous filter-chain entry
        // would leak its principal as if it were a real org binding.
        UsernamePasswordAuthenticationToken unauth =
                new UsernamePasswordAuthenticationToken(buildUserDetails("should-not-leak"), null);
        unauth.setAuthenticated(false);
        SecurityContextHolder.setContext(new SecurityContextImpl(unauth));

        String observed = AgentContextHolder.getOrgId();

        assertThat(observed)
                .as("unauthenticated Authentication must not yield an orgId")
                .isNull();
    }

    @Test
    void scopedValueUnbound_principalIsNotUserDetailsImpl_returnsNull() {
        // SecurityContext may carry a different principal type (e.g. String
        // for anonymous filter entries, or a custom OAuth principal). Only
        // UserDetailsImpl exposes getOrgId(); other types must safely fall
        // through to null, not throw.
        UsernamePasswordAuthenticationToken nonUserDetails =
                new UsernamePasswordAuthenticationToken("anonymous-string-principal", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.setContext(new SecurityContextImpl(nonUserDetails));

        String observed = AgentContextHolder.getOrgId();

        assertThat(observed)
                .as("non-UserDetailsImpl principal must fall through to null without throwing")
                .isNull();
    }

    private UserDetailsImpl buildUserDetails(String orgId) {
        return new UserDetailsImpl(
                UUID.randomUUID(),
                "test-user",
                "test-user@test.local",
                orgId,
                false,
                "no-password",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private void setSecurityContext(UserDetailsImpl principal) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }
}
