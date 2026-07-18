package com.operativus.agentmanager.control.config;

import com.operativus.agentmanager.core.model.SecurityPrincipals;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

/**
 * Domain Responsibility: Configures JPA Auditing capabilities to automatically populate entity lifecycle timestamps and creators.
 * State: Stateless (Configuration)
 */
@Configuration
public class AuditConfig {

    /**
     * @summary Provides the current auditor's identity for auditing purposes.
     * @logic
     * - Defines an `AuditorAware` bean.
     * - Currently acts as a placeholder returning a static "system" user.
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal().equals(SecurityPrincipals.ANONYMOUS_USER)) {
                return Optional.empty();
            }
            return Optional.of(authentication.getName());
        };
    }
}
