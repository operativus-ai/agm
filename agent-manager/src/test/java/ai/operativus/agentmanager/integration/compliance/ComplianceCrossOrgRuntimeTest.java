package ai.operativus.agentmanager.integration.compliance;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the cross-org isolation gate added by PRs #931 / #932 / #933
 * on {@link ai.operativus.agentmanager.control.controller.ComplianceController}.
 * In-process equivalent of {@code scripts/walkthroughs/27-compliance-cross-org-probe.sh}
 * — verifies that {@code requireSameOrgOrSelf} returns 404 (existence-leak protection)
 * when an ADMIN in one org targets a userId that lives in a different org.
 *
 * Before PR #931, the four compliance endpoints relied solely on
 * {@code @PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")}. The
 * ADMIN branch had no tenant scope, so any cross-org ADMIN could export, request
 * erasure on, or hard-delete any user's data — a P0 GDPR data-portability gap.
 *
 * PR #931 added {@code requireSameOrgOrSelf(userId, auth)} which looks up the target
 * via {@code UserRepository.findByUsername} and compares {@code User.getOrgId()} to
 * {@code AgentContextHolder.getOrgId()}. Mismatch → {@code ResourceNotFoundException}
 * → 404. The 404 (rather than 403) follows the existence-leak-protection convention
 * used by the agent / workflow / KB controllers — a foreign-org admin shouldn't be
 * able to distinguish "user exists elsewhere" from "user does not exist."
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Why this test exists alongside {@link ComplianceAdminAuthzRuntimeTest}: that class
 * verifies the role-tier matrix (anonymous → 401, ROLE_USER → 403, ROLE_ADMIN →
 * gate-cleared) but does NOT exercise the cross-org case. A different-org ADMIN
 * caller fully clears {@code @PreAuthorize("hasRole('ADMIN')")} — the gate is
 * service-side via {@code requireSameOrgOrSelf}, and is invisible to the authz
 * test class above. This test class is the regression guard for that gap.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ComplianceCrossOrgRuntimeTest extends BaseIntegrationTest {

    private static final String CALLER_ORG  = "wt27-org-caller";
    private static final String FOREIGN_ORG = "wt27-org-foreign";

    // ─── GET /api/compliance/export/{userId} ────────────────────────────────────

    @Test
    void exportUserData_crossOrgAdminTargetingForeignUser_returns404() {
        Fixture fx = setupCrossOrgFixture("export");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/compliance/export/" + fx.foreignUsername),
                HttpMethod.GET, new HttpEntity<>(fx.callerAuth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PR #931: ADMIN in CALLER_ORG targeting a user in FOREIGN_ORG must get 404, "
                        + "not 200 (the pre-fix data-portability leak) and not 403 (which would "
                        + "leak existence of the foreign user). Got: " + resp.getStatusCode());
    }

    // ─── POST /api/compliance/erasure-requests?userId=… ────────────────────────

    @Test
    void submitErasureRequest_crossOrgAdminTargetingForeignUser_returns404() {
        Fixture fx = setupCrossOrgFixture("submit");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/compliance/erasure-requests?userId=" + fx.foreignUsername),
                HttpMethod.POST, new HttpEntity<>(Map.of(), fx.callerAuth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PR #931 + #932: foreign-org erasure-request POST must 404, not 201; "
                        + "pre-fix the request would have been written to erasure_requests "
                        + "without an org_id scope. Got: " + resp.getStatusCode());
    }

    // ─── GET /api/compliance/erasure-requests?userId=… ─────────────────────────

    @Test
    void listErasureRequests_crossOrgAdminTargetingForeignUser_returns404() {
        Fixture fx = setupCrossOrgFixture("list");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/compliance/erasure-requests?userId=" + fx.foreignUsername),
                HttpMethod.GET, new HttpEntity<>(fx.callerAuth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PR #931 + #933: foreign-org erasure-request listing must 404, not 200 with "
                        + "the foreign user's request history. Got: " + resp.getStatusCode());
    }

    // ─── DELETE /api/compliance/erase/{userId} ─────────────────────────────────

    @Test
    void eraseUserData_crossOrgAdminTargetingForeignUser_returns404() {
        Fixture fx = setupCrossOrgFixture("erase");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/compliance/erase/" + fx.foreignUsername),
                HttpMethod.DELETE, new HttpEntity<>(fx.callerAuth), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PR #931: foreign-org hard-erase must 404, not 200/204 (the pre-fix path "
                        + "would have purged the foreign user's data). Got: "
                        + resp.getStatusCode());
    }

    // ─── Sanity probe: same-org admin still works ──────────────────────────────

    @Test
    void exportUserData_sameOrgAdminTargetingForeignUser_clearsGate() {
        // Both caller and target in the SAME org → requireSameOrgOrSelf passes →
        // request reaches the service layer. Asserts only that it's NOT 404 (the
        // cross-org rejection); downstream 200 / 404-from-missing-rows is out of
        // scope (this test verifies the AUTHZ GATE, not the service contract).
        Fixture fx = setupSameOrgFixture("same-org-sanity");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/compliance/export/" + fx.foreignUsername),
                HttpMethod.GET, new HttpEntity<>(fx.callerAuth), String.class);

        assertTrue(resp.getStatusCode() != HttpStatus.NOT_FOUND
                        && resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "same-org admin must clear requireSameOrgOrSelf — neither 401, 403, nor 404. "
                        + "Got: " + resp.getStatusCode());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private record Fixture(HttpHeaders callerAuth, String foreignUsername) {}

    private Fixture setupCrossOrgFixture(String tag) {
        String suffix          = tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        String callerUsername  = "wt27-caller-" + suffix;
        String foreignUsername = "wt27-foreign-" + suffix;

        HttpHeaders callerAuth = registerLoginWithOrg(callerUsername, CALLER_ORG);
        // Seed the foreign user so requireSameOrgOrSelf's UserRepository.findByUsername
        // returns a non-null row in a DIFFERENT org. We don't need their auth headers.
        registerLoginWithOrg(foreignUsername, FOREIGN_ORG);

        // Precondition: requireSameOrgOrSelf throws the SAME ResourceNotFoundException
        // ("User not found ...") in both the missing-user path and the wrong-org path
        // by design (existence-leak protection). The 404 below is therefore ambiguous
        // unless we PROVE the foreign user is persisted in FOREIGN_ORG — otherwise a
        // future regression of registerLoginWithOrg or its JDBC backfill would let the
        // test silently pass via the missing-user branch instead of the org-mismatch
        // branch this test class is meant to pin.
        Long foreignExists = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ? AND org_id = ?",
                Long.class, foreignUsername, FOREIGN_ORG);
        assertEquals(1L, foreignExists,
                "precondition: foreign user must be persisted in FOREIGN_ORG so the 404 "
                        + "below exercises the org-mismatch branch, not the missing-user branch");

        return new Fixture(callerAuth, foreignUsername);
    }

    private Fixture setupSameOrgFixture(String tag) {
        String suffix         = tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        String callerUsername = "wt27-caller-" + suffix;
        String targetUsername = "wt27-sameorg-target-" + suffix;

        HttpHeaders callerAuth = registerLoginWithOrg(callerUsername, CALLER_ORG);
        registerLoginWithOrg(targetUsername, CALLER_ORG);

        // Precondition: target must be persisted in the SAME org as the caller so the
        // "clears gate" assertion below is exercising the same-org branch, not just
        // missing-user (which would coincidentally also pass the != 404 check).
        Long targetExists = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ? AND org_id = ?",
                Long.class, targetUsername, CALLER_ORG);
        assertEquals(1L, targetExists,
                "precondition: same-org target must be persisted in CALLER_ORG so the "
                        + "gate-clearing assertion exercises the same-org branch");

        return new Fixture(callerAuth, targetUsername);
    }
}
