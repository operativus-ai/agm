package com.operativus.agentmanager.control.security;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Domain Responsibility: Binds {@link AgentContextHolder#orgId} for the duration of each request
 *   so downstream services, filters, and advisors (including
 *   {@link com.operativus.agentmanager.compute.config.AgentMdcFilter}) can rely on
 *   {@code AgentContextHolder.getOrgId()} returning the caller's tenant.
 *
 * <p>Resolution precedence (first non-blank wins):</p>
 * <ol>
 *   <li>{@code org_id} claim from the JWT in the {@code Authorization: Bearer ...} header.</li>
 *   <li>{@code X-Org-Id} explicit header.</li>
 *   <li>{@link UserDetailsImpl#getOrgId()} from the authenticated principal, if Spring Security
 *       has already populated the {@link SecurityContextHolder}.</li>
 * </ol>
 *
 * <p>If none apply, the ScopedValue is left unbound and {@code AgentContextHolder.getOrgId()}
 * returns {@code null} — same as the pre-filter behavior, so existing super-admin /
 * unauthenticated flows keep working without regression.</p>
 *
 * <p>Ordering: runs BEFORE {@link com.operativus.agentmanager.compute.config.AgentMdcFilter}
 * ({@code HIGHEST_PRECEDENCE + 10}) so the ScopedValue is bound before MDC reads it. The
 * {@code HIGHEST_PRECEDENCE + 5} slot keeps it above app-level filters while staying below
 * Spring Security's filter chain; the JWT claim fast-path lets us resolve the tenant without
 * waiting for the full security chain to run.</p>
 *
 * State: Stateless
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Org-Id";
    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    private final JwtUtils jwtUtils;

    public TenantContextFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String orgId = resolveOrgId(request);
        if (orgId == null || orgId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            ScopedValue.where(AgentContextHolder.orgId, orgId).call(() -> {
                filterChain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private String resolveOrgId(HttpServletRequest request) {
        String fromJwt = extractOrgIdFromJwt(request);
        if (fromJwt != null && !fromJwt.isBlank()) {
            return fromJwt;
        }

        String fromHeader = request.getHeader(TENANT_HEADER);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }

        return extractOrgIdFromAuthenticatedUser();
    }

    private String extractOrgIdFromJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader(AUTH_HEADER);
        if (!StringUtils.hasText(headerAuth) || !headerAuth.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = headerAuth.substring(BEARER_PREFIX.length());
        try {
            if (!jwtUtils.validateJwtToken(token)) {
                return null;
            }
            return jwtUtils.getOrgIdFromJwtToken(token);
        } catch (Exception e) {
            log.debug("Skipping JWT org_id extraction: {}", e.getMessage());
            return null;
        }
    }

    private String extractOrgIdFromAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetailsImpl userDetails) {
            return userDetails.getOrgId();
        }
        return null;
    }
}
