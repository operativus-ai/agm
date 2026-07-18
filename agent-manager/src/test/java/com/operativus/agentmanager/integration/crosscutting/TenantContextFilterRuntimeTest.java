package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.control.security.JwtUtils;
import com.operativus.agentmanager.control.security.TenantContextFilter;
import com.operativus.agentmanager.control.security.UserDetailsImpl;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runtime contract: {@link TenantContextFilter} binds {@link AgentContextHolder#orgId} for
 * the lifetime of the downstream filter chain, honoring a three-tier precedence
 * (JWT {@code org_id} claim > {@code X-Org-Id} header > authenticated {@code UserDetailsImpl}),
 * and leaves the ScopedValue unbound when none apply.
 *
 * <p>No Spring context is needed — a {@link FilterChain} lambda (SAM) stands in for the
 * downstream chain; {@code MockFilterChain} can't be used here because it requires a
 * {@code Filter} varargs tail and {@code Filter} is not a SAM interface (has init/destroy).</p>
 */
@Tag("integration")
class TenantContextFilterRuntimeTest {

    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final TenantContextFilter filter = new TenantContextFilter(jwtUtils);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void headerPresent_bindsOrgIdScopedValueDuringChain() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        req.addHeader(TenantContextFilter.TENANT_HEADER, "acme-org");
        var res = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        FilterChain chain = (request, response) -> observed.set(AgentContextHolder.getOrgId());

        filter.doFilter(req, res, chain);

        assertThat(observed.get()).isEqualTo("acme-org");
    }

    @Test
    void headerAbsent_orgIdScopedValueRemainsUnbound() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>("sentinel");

        FilterChain chain = (request, response) -> observed.set(AgentContextHolder.getOrgId());

        filter.doFilter(req, res, chain);

        assertThat(observed.get()).isNull();
    }

    @Test
    void headerBlank_orgIdScopedValueRemainsUnbound() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        req.addHeader(TenantContextFilter.TENANT_HEADER, "   ");
        var res = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>("sentinel");

        FilterChain chain = (request, response) -> observed.set(AgentContextHolder.getOrgId());

        filter.doFilter(req, res, chain);

        assertThat(observed.get()).isNull();
    }

    @Test
    void scopedValueDoesNotLeakAfterRequest() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        req.addHeader(TenantContextFilter.TENANT_HEADER, "bound-during-chain");
        var res = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> { };

        filter.doFilter(req, res, chain);

        assertThat(AgentContextHolder.getOrgId()).isNull();
    }

    @Test
    void ioExceptionFromChainPropagates() {
        var req = new MockHttpServletRequest();
        req.addHeader(TenantContextFilter.TENANT_HEADER, "acme-org");
        var res = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> { throw new IOException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(req, res, chain))
                .isInstanceOf(IOException.class)
                .hasMessage("boom");
    }

    @Test
    void servletExceptionFromChainPropagates() {
        var req = new MockHttpServletRequest();
        req.addHeader(TenantContextFilter.TENANT_HEADER, "acme-org");
        var res = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> { throw new ServletException("chain failed"); };

        assertThatThrownBy(() -> filter.doFilter(req, res, chain))
                .isInstanceOf(ServletException.class)
                .hasMessage("chain failed");
    }

    @Test
    void jwtOrgIdClaimTakesPrecedenceOverHeader() throws ServletException, IOException {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer test-token");
        req.addHeader(TenantContextFilter.TENANT_HEADER, "header-org");
        var res = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        when(jwtUtils.validateJwtToken(eq("test-token"))).thenReturn(true);
        when(jwtUtils.getOrgIdFromJwtToken(eq("test-token"))).thenReturn("jwt-org");

        FilterChain chain = (request, response) -> observed.set(AgentContextHolder.getOrgId());

        filter.doFilter(req, res, chain);

        assertThat(observed.get()).isEqualTo("jwt-org");
    }

    @Test
    void authenticatedUserOrgIdFallsBackWhenHeaderMissing() throws ServletException, IOException {
        UserDetailsImpl principal = new UserDetailsImpl(
                UUID.randomUUID(),
                "alice",
                "alice@example.com",
                "seeded",
                false,
                "pw",
                List.of(new SimpleGrantedAuthority("USER")));
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        FilterChain chain = (request, response) -> observed.set(AgentContextHolder.getOrgId());

        filter.doFilter(req, res, chain);

        assertThat(observed.get()).isEqualTo("seeded");
    }
}
