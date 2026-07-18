package com.operativus.agentmanager.control.config;

import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.enums.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Domain Responsibility: First-admin bootstrap. On a fresh deployment, self-registration only ever
 *     grants {@code ROLE_USER} and LLM provider keys are admin-gated + DB-only, so day-1 setup
 *     otherwise requires manual SQL role promotion. When explicitly enabled, this runner creates a
 *     single admin account at startup from configuration so the operator can immediately configure
 *     the platform (provider credentials, etc.).
 *
 *     <p><b>Disabled by default</b> ({@code agentmanager.bootstrap.admin.enabled=false}) — never
 *     auto-creates an admin on an ordinary boot. Enable it for the very first start, then turn it
 *     back off. <b>Idempotent:</b> if a user with the configured username OR email already exists,
 *     it logs and skips (safe across restarts; no password reset on every boot). <b>Fail-fast:</b>
 *     enabling it with a missing/blank username, email, or password (or an invalid role) aborts
 *     startup — opting in with broken credentials is a hard misconfiguration the operator must see.
 *
 *     <p>Unlike public self-registration ({@code AuthController.registerUser}, which leaves
 *     {@code org_id} null), the bootstrap admin is stamped with an org ({@code org-id}, default
 *     {@link TenantConstants#DEFAULT_SYSTEM_ORG}) so it never trips the null-org fail-closed paths.
 * State: Stateless (Spring singleton; runs once at startup).
 */
@Component
@ConditionalOnProperty(prefix = "agentmanager.bootstrap.admin", name = "enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String email;
    private final String password;
    private final String orgId;
    private final String role;

    public AdminBootstrapRunner(UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                @Value("${agentmanager.bootstrap.admin.username:}") String username,
                                @Value("${agentmanager.bootstrap.admin.email:}") String email,
                                @Value("${agentmanager.bootstrap.admin.password:}") String password,
                                @Value("${agentmanager.bootstrap.admin.org-id:" + TenantConstants.DEFAULT_SYSTEM_ORG + "}") String orgId,
                                @Value("${agentmanager.bootstrap.admin.role:ROLE_SUPER_ADMIN}") String role) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.email = email;
        this.password = password;
        this.orgId = orgId;
        this.role = role;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isBlank(username) || isBlank(email) || isBlank(password)) {
            throw new IllegalStateException(
                    "agentmanager.bootstrap.admin.enabled=true requires non-blank "
                    + "agentmanager.bootstrap.admin.{username,email,password}");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "agentmanager.bootstrap.admin.password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        RoleType adminRole;
        try {
            adminRole = RoleType.fromValue(role);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("agentmanager.bootstrap.admin.role is not a valid role: " + role, ex);
        }

        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            log.info("Admin bootstrap: a user with username '{}' or that email already exists — skipping", username);
            return;
        }

        String resolvedOrg = isBlank(orgId) ? TenantConstants.DEFAULT_SYSTEM_ORG : orgId;
        User user = new User(username, email, passwordEncoder.encode(password));
        user.setRoles(new HashSet<>(Set.of(adminRole)));
        user.setOrgId(resolvedOrg);
        userRepository.save(user);

        log.warn("Admin bootstrap: created {} '{}' (org='{}'). "
                 + "Set agentmanager.bootstrap.admin.enabled=false now that the admin exists.",
                adminRole, username, resolvedOrg);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
