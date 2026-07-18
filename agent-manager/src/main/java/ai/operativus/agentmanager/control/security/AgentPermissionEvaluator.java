package ai.operativus.agentmanager.control.security;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Domain Responsibility: Resolves {@code hasPermission(#id, 'AgentDefinition', <action>)}
 *   SpEL authorization on {@code AgentAdminService}. Enforces the role gate only
 *   ({@code ROLE_ADMIN}); tenant scoping is enforced in the service body via
 *   {@code findByIdAndOrgId} so cross-tenant requests surface as 404, not 403 — this
 *   matches the existence-leak-protection pattern used by the knowledge / schedules /
 *   workflows / teams tenant-isolation surfaces.
 * State: Stateless
 *
 * <p>Scope intentionally minimum-viable for §24 matrix: no per-agent ACL table yet. When
 * finer-grained delegation is needed (§25 owner/shared/public), extend with an ACL lookup.
 */
@Component
public class AgentPermissionEvaluator implements PermissionEvaluator {

    private static final String TARGET_TYPE = "AgentDefinition";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        return false;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId,
                                 String targetType, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!TARGET_TYPE.equals(targetType)) {
            return false;
        }
        return isAdmin(authentication);
    }

    private static boolean isAdmin(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ADMIN_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
