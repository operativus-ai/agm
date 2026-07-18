package com.operativus.agentmanager.control.security;

import com.operativus.agentmanager.core.model.enums.RoleType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * Domain Responsibility: Spring Security {@link Authentication} token representing a
 * successfully validated machine-to-machine A2A API key principal.
 *
 * Gap 2.3 Implementation: Provides the headless authentication identity for peer agents
 * calling AGM over the {@code /api/v1/a2a/**} networking boundary. Unlike the JWT-based
 * {@code UsernamePasswordAuthenticationToken} used by human sessions, this token carries:
 *  - The raw API key identifier (not the secret itself) as the principal name.
 *  - A fixed {@code ROLE_A2A_AGENT} authority that security rules can match against.
 *  - An authenticated flag set only after the key has been validated by the filter.
 *
 * Design:
 * - Immutable record-style implementation (all fields set at construction, no mutation).
 * - Credentials are cleared immediately after construction to prevent leakage in logs or
 *   serialized security context snapshots.
 */
public final class ApiKeyAuthentication implements Authentication {

    private final String keyId;
    private final Collection<GrantedAuthority> authorities;
    private boolean authenticated;

    /**
     * @param keyId         The opaque key identifier (not the raw secret).
     * @param authenticated {@code true} after the key has passed validation.
     */
    public ApiKeyAuthentication(String keyId, boolean authenticated) {
        this.keyId = keyId;
        this.authorities = List.of(new SimpleGrantedAuthority(RoleType.ROLE_A2A_AGENT.getValue()));
        this.authenticated = authenticated;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /** Returns null — credentials are not stored post-validation. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    /** The principal name is the key identifier, not the secret. */
    @Override
    public Object getPrincipal() {
        return keyId;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        this.authenticated = isAuthenticated;
    }

    /** Returns the key identifier for display in audit logs. */
    @Override
    public String getName() {
        return keyId;
    }
}
