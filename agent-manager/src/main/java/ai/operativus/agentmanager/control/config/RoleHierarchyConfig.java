package ai.operativus.agentmanager.control.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

/**
 * Configures Spring Security's role hierarchy for fine-grained RBAC.
 *
 * Hierarchy:
 *   SUPER_ADMIN > ADMIN > OPERATOR > USER > VIEWER
 *
 * A SUPER_ADMIN automatically inherits all permissions of ADMIN, OPERATOR, USER, and VIEWER.
 * An ADMIN inherits OPERATOR, USER, and VIEWER permissions. And so on.
 */
@Configuration
public class RoleHierarchyConfig {

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_SUPER_ADMIN > ROLE_ADMIN
                ROLE_ADMIN > ROLE_OPERATOR
                ROLE_OPERATOR > ROLE_USER
                ROLE_USER > ROLE_VIEWER
                """);
    }
}
