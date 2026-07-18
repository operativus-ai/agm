package ai.operativus.agentmanager.control.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Domain Responsibility: Shared principal-resolution helpers for HTTP-side caller
 *   identity. Resolves the org_id and username claims from the {@code SecurityContext}
 *   principal so controllers in any package can stamp tenant-scoped operations and
 *   identity-sensitive guards without each one re-implementing the cast.
 * State: Stateless utility class.
 *
 * <p>Lives in {@code control/security/} alongside {@link UserDetailsImpl} since both
 * deal with HTTP-side caller identity. Public so controllers in any package can
 * resolve caller claims without re-implementing the principal cast.
 *
 * <p>Returning null from {@link #resolveCallerOrgId()} would broaden queries to every
 * tenant's rows; callers must enforce {@code requireNonNull} (or treat null as a hard
 * error) before passing the result to the service layer.
 */
public final class CallerContext {

    private CallerContext() {}

    /**
     * Returns the authenticated caller's {@code orgId} from the SecurityContext principal.
     * Returns null if no principal is bound — callers must treat null as a hard error.
     * Cross-tenant visibility would require a dedicated super-admin role that does not
     * exist today.
     */
    public static String resolveCallerOrgId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getOrgId();
        }
        return null;
    }

    /**
     * Returns the authenticated caller's username from the SecurityContext principal.
     * Returns null if no principal is bound — callers depending on this for identity-
     * sensitive checks (self-delete guard, etc.) must reject the null case explicitly.
     */
    public static String resolveCallerUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) {
            return ud.getUsername();
        }
        return null;
    }
}
