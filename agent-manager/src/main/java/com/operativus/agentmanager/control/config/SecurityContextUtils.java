package com.operativus.agentmanager.control.config;

import com.operativus.agentmanager.core.model.SecurityPrincipals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared utility for resolving the current authenticated user identity.
 * Replaces duplicated "default-user" fallback patterns across controllers, services, and tools.
 */
public final class SecurityContextUtils {

    private static final Logger log = LoggerFactory.getLogger(SecurityContextUtils.class);
    private static final String FALLBACK_USER = "default-user";

    private SecurityContextUtils() {}

    /**
     * Resolves the current user from SecurityContextHolder.
     * Falls back to "default-user" if no auth context is available, logging a warning.
     */
    public static String resolveCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !SecurityPrincipals.ANONYMOUS_USER.equals(auth.getPrincipal())) {
            return auth.getName();
        }
        log.debug("No authenticated user in SecurityContext — falling back to '{}'", FALLBACK_USER);
        return FALLBACK_USER;
    }

    /**
     * Resolves user from an explicit parameter, falling back to SecurityContext, then to "default-user".
     */
    public static String resolveUserId(String explicitUserId) {
        if (explicitUserId != null && !explicitUserId.isBlank()) {
            return explicitUserId;
        }
        return resolveCurrentUserId();
    }
}
