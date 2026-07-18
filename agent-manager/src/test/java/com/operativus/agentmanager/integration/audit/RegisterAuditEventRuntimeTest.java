package com.operativus.agentmanager.integration.audit;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins {@code AuthController.registerUser}'s REGISTER audit-row
 *   write — every successful POST to {@code /api/auth/register} produces exactly one
 *   {@code system_audits} row with {@code action=REGISTER} and the new user's username.
 *
 *   <p><b>LOGOUT scope intentionally omitted</b>: a LOGOUT audit event is referenced in
 *   {@code AuthController}'s docstring + {@code SystemAuditInterceptor}'s docstring +
 *   {@code SystemAuditEntity}'s docstring, but no {@code /logout} endpoint exists and no
 *   code writes a LOGOUT row. The feature is documented but unimplemented (same pattern as
 *   refresh-token and MFA — recon confirmed during this session's prior PRs). When LOGOUT
 *   is added, a sibling test should pin its audit-row write.
 *
 *   <p>Complements {@code LoginFailureAuditRuntimeTest} (LOGIN_FAILURE) and the existing
 *   {@code AuditLogsRuntimeTest.authEventsShouldProduceAuditRows} (LOGIN_SUCCESS +
 *   LOGIN_FAILURE smoke).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RegisterAuditEventRuntimeTest extends BaseIntegrationTest {

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void registerWritesOneSystemAuditRowWithActionRegister() {
        String username = "register-audit-target";

        // authenticateAs does register-then-login. We need to count BEFORE any audit
        // writes — assert the delta after authenticateAs equals 1 (REGISTER) +
        // 1 (LOGIN_SUCCESS from the login phase) = 2 rows total for this username.
        long baselineRegister = countRows(username, "REGISTER");
        long baselineLogin = countRows(username, "LOGIN_SUCCESS");

        authenticateAs(username, "register-audit-target@test.local",
                "pass-rat-1234", List.of("ROLE_USER"));

        long afterRegister = countRows(username, "REGISTER");
        long afterLogin = countRows(username, "LOGIN_SUCCESS");

        assertEquals(baselineRegister + 1, afterRegister,
                "register must write exactly 1 REGISTER row for the username; baseline="
                        + baselineRegister + " after=" + afterRegister);
        assertEquals(baselineLogin + 1, afterLogin,
                "subsequent login in authenticateAs flow must also write 1 LOGIN_SUCCESS "
                        + "row (sanity); baseline=" + baselineLogin + " after=" + afterLogin);
    }

    @Test
    void registerAuditRowCarriesUsernameAndCorrectAction() {
        String username = "register-audit-content";
        authenticateAs(username, "register-audit-content@test.local",
                "pass-rac-1234", List.of("ROLE_USER"));

        // Inspect the REGISTER row directly.
        Long registerRowsWithCorrectFields = jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_audits
                WHERE username = ?
                  AND action = 'REGISTER'
                """, Long.class, username);

        assertEquals(1L, registerRowsWithCorrectFields,
                "exactly one REGISTER row must exist with username=" + username);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private long countRows(String username, String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE username = ? AND action = ?",
                Long.class, username, action);
    }
}
