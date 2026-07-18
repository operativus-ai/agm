package com.operativus.agentmanager.integration.config;

import com.operativus.agentmanager.control.config.AdminBootstrapRunner;
import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.core.model.enums.RoleType;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Proves the first-admin bootstrap wiring end to end — with
 *   {@code agentmanager.bootstrap.admin.enabled=true} the {@link AdminBootstrapRunner} bean is
 *   created ({@code @ConditionalOnProperty}), its {@code @Value} config binds, and running it
 *   persists a single admin against real Postgres + the real BCrypt {@code PasswordEncoder}, with
 *   the configured role and org stamped (unlike public self-registration). Re-running is idempotent.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
@TestPropertySource(properties = {
        "agentmanager.bootstrap.admin.enabled=true",
        "agentmanager.bootstrap.admin.username=bootstrap-admin",
        "agentmanager.bootstrap.admin.email=bootstrap-admin@example.com",
        "agentmanager.bootstrap.admin.password=bootstrapPass123",
        "agentmanager.bootstrap.admin.org-id=BOOTSTRAP_ORG",
        "agentmanager.bootstrap.admin.role=ROLE_SUPER_ADMIN"
})
public class AdminBootstrapRuntimeTest extends BaseIntegrationTest {

    @Autowired private AdminBootstrapRunner runner;
    @Autowired private UserRepository userRepository;

    @Test
    void enabledRunner_createsConfiguredAdmin_withRoleOrgAndBcryptPassword_idempotently() {
        // The runner already fired at context startup; BaseIntegrationTest.truncateDatabase()
        // (@BeforeEach) wiped that row. Re-run the WIRED bean to validate the
        // @ConditionalOnProperty + @Value binding and the create path against real infra.
        runner.run(null);

        User u = userRepository.findByUsername("bootstrap-admin").orElseThrow(
                () -> new AssertionError("bootstrap admin was not created"));
        assertEquals("bootstrap-admin@example.com", u.getEmail());
        assertEquals("BOOTSTRAP_ORG", u.getOrgId(),
                "bootstrap admin must be stamped with the configured org (self-registration leaves it null)");
        assertTrue(u.getRoles().contains(RoleType.ROLE_SUPER_ADMIN));
        assertTrue(u.getPassword().startsWith("$2"), "password must be BCrypt-hashed, never raw");

        // Idempotent: a second run neither throws (unique constraint) nor duplicates the user.
        runner.run(null);
        long count = userRepository.findAll().stream()
                .filter(x -> "bootstrap-admin".equals(x.getUsername())).count();
        assertEquals(1, count, "re-running the bootstrap must not create a duplicate admin");
    }
}
