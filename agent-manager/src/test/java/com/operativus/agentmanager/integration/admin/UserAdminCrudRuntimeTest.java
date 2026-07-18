package com.operativus.agentmanager.integration.admin;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box pins on the unhappy paths of {@code UserAdminController}
 *   beyond what {@link UserAdminRuntimeTest} covers. Existing test pins the happy lifecycle
 *   (list, role promotion, disable, delete, bulkCreate idempotency); this fills the
 *   admin-REST negative-space (duplicate username/email via admin API, missing-id paths).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Plan: {@code .claude/plans/users-runtime-coverage-2026-05-16.md} pins A1-A2, A5-A7.
 *
 * <p>A3 / A4 (GET /{id} happy + 404) were dropped from the plan after verifying
 * {@code UserAdminController} has no per-id GET endpoint — only list, create, update,
 * delete, reset-password, bulk. Adding a GET endpoint is feature work, not a test gap.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class UserAdminCrudRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── A1 / A2: createUser duplicate guards via admin API ───

    // A1 — POST /api/admin/users with a username that already exists -> 400. The /register
    //      path covers this for the self-registration flow; this pin closes the admin path.
    @Test
    void createUserViaAdmin_duplicateUsername_returns400() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("up1-a1-admin-" + tag);

        // Seed the colliding username via the admin API itself, then attempt to re-create.
        String existing = "up1-a1-victim-" + tag;
        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", existing,
                        "email", existing + "@test.local",
                        "password", "pass-1234",
                        "roles", List.of("ROLE_VIEWER")), auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), "fixture: first create must succeed");

        // Second create with same username, different email -> must be rejected.
        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", existing,
                        "email", "different-" + tag + "@test.local",
                        "password", "pass-1234",
                        "roles", List.of("ROLE_VIEWER")), auth),
                JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, second.getStatusCode(),
                "duplicate username via admin API must return 400 — pins UserAdminService.createUser:50");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, existing);
        assertEquals(1, count, "the duplicate attempt must NOT have inserted a second row");
    }

    // A2 — POST /api/admin/users with an email that already exists -> 400.
    @Test
    void createUserViaAdmin_duplicateEmail_returns400() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("up1-a2-admin-" + tag);

        String email = "up1-a2-shared-" + tag + "@test.local";
        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", "up1-a2-user-a-" + tag,
                        "email", email,
                        "password", "pass-1234",
                        "roles", List.of("ROLE_VIEWER")), auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", "up1-a2-user-b-" + tag,
                        "email", email,
                        "password", "pass-1234",
                        "roles", List.of("ROLE_VIEWER")), auth),
                JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, second.getStatusCode(),
                "duplicate email via admin API must return 400 — pins UserAdminService.createUser:53");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertEquals(1, count, "duplicate email attempt must NOT have inserted a second row");
    }

    // ─── A5: updateUser email-change duplicate -> 400 ───

    // A5 — PUT /api/admin/users/{userA} setting email to userB's email -> 400. Pins the
    //      existsByEmail guard at UserAdminService.updateUser:67, which only fires when the
    //      new email differs from the user's current email.
    @Test
    void updateUserEmail_collidesWithExistingUser_returns400() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("up1-a5-admin-" + tag);

        String emailA = "up1-a5-userA-" + tag + "@test.local";
        String emailB = "up1-a5-userB-" + tag + "@test.local";

        UUID userAId = createUserGetId(auth, "up1-a5-userA-" + tag, emailA);
        createUserGetId(auth, "up1-a5-userB-" + tag, emailB);

        // Try to update userA's email to userB's email.
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/" + userAId),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("email", emailB), auth),
                JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "updating to an already-taken email must return 400 — pins updateUser:67 existsByEmail guard");

        String stillEmailA = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, userAId);
        assertEquals(emailA, stillEmailA, "userA's email must be unchanged after the rejected update");
    }

    // ─── A6 / A7: missing user id paths ───

    // A6 — PUT /api/admin/users/{missing UUID} -> 404. UserAdminService.getUser throws
    //      ResourceNotFoundException (fix/user-admin-getuser-resource-not-found switched
    //      this from BusinessValidationException so the wire response now matches the
    //      convention used by ApprovalService and other services).
    @Test
    void updateUserOnMissingId_returns404() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("up1-a6-admin-" + tag);

        UUID missing = UUID.randomUUID();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/users/" + missing),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("email", "anything-" + tag + "@test.local"), auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PUT on missing user id must return 404 — ResourceNotFoundException from getUser");
    }

    // A7 — DELETE /api/admin/users/{missing UUID} -> 404, same getUser->RNFE path as A6.
    @Test
    void deleteUserOnMissingId_returns404() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("up1-a7-admin-" + tag);

        UUID missing = UUID.randomUUID();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/users/" + missing),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "DELETE on missing user id must return 404 — same getUser->RNFE path as A6");
        // Defensive: confirm no orphan row was created as a side effect.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, missing);
        assertEquals(0, count, "missing-id DELETE must not touch the users table");
    }

    // ─── B: resetPassword endpoint (entirely unpinned before this PR) ───

    // B1 — POST /api/admin/users/{id}/reset-password rotates the password: old credentials
    //      stop working, new credentials log in successfully. Pins the full round-trip
    //      because the underlying service is uncovered at runtime today.
    @Test
    void resetPassword_happyPath_oldPasswordRejected_newPasswordWorks() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("up2-b1-admin-" + tag);

        // Seed a user we can log in as. Use authenticateAs so the password is bcrypt-hashed
        // by the real registration path (mirrors production), then capture the user id.
        String username = "up2-b1-victim-" + tag;
        String oldPassword = "old-pass-1234";
        String newPassword = "fresh-pass-5678";
        registerLogin(username, oldPassword);
        UUID userId = UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, username));

        // Reset via admin API.
        ResponseEntity<Void> reset = rest.exchange(
                url("/api/admin/users/" + userId + "/reset-password"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", newPassword), auth),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, reset.getStatusCode(),
                "reset-password returns 204 (ResponseEntity.noContent)");

        // Old credentials must now fail.
        ResponseEntity<Map<String, Object>> oldLogin = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", oldPassword), HttpHeaders.EMPTY),
                JSON_MAP);
        assertEquals(HttpStatus.UNAUTHORIZED, oldLogin.getStatusCode(),
                "old password must be rejected after reset");

        // New credentials must succeed and return a JWT.
        ResponseEntity<Map<String, Object>> newLogin = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", newPassword), HttpHeaders.EMPTY),
                JSON_MAP);
        assertEquals(HttpStatus.OK, newLogin.getStatusCode(),
                "new password must succeed after reset");
        assertTrue(newLogin.getBody().get("token") != null
                        && !((String) newLogin.getBody().get("token")).isBlank(),
                "successful login response must carry a non-blank JWT");
    }

    // B2 — POST /api/admin/users/{missing UUID}/reset-password -> 404 (same getUser->RNFE
    //      path as A6/A7).
    @Test
    void resetPasswordOnMissingId_returns404() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("up2-b2-admin-" + tag);

        UUID missing = UUID.randomUUID();
        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/users/" + missing + "/reset-password"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "irrelevant-1234"), auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "reset-password on missing id must return 404 — same getUser->RNFE path as A6/A7");
    }

    // ─── C: audit emission on auth flows ───

    // C1 — Successful login emits exactly one system_audits row with action=LOGIN_SUCCESS,
    //      resource_type=AUTH, request_path=/api/auth/login, response_status=200, and
    //      username matching the actor. Pins AuthController.authenticateUser:92-99.
    @Test
    void successfulLogin_emitsLoginSuccessSystemAuditRow() {
        String tag = shortUuid();
        String username = "up2-c1-actor-" + tag;
        registerLogin(username, "pass-c1-1234");

        // The registerLogin helper calls authenticateAs which goes through the real
        // /api/auth/login endpoint, so the audit row is already emitted.
        Integer successCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM system_audits
                WHERE username = ? AND action = 'LOGIN_SUCCESS'
                  AND resource_type = 'AUTH' AND request_path = '/api/auth/login'
                  AND response_status = 200
                """,
                Integer.class, username);
        assertEquals(1, successCount,
                "successful login must emit exactly one LOGIN_SUCCESS row in system_audits");
    }

    // C2 — Failed login (wrong password) emits exactly one LOGIN_FAILURE row with
    //      response_status=401 and org_id=NULL (the failure path passes `null` to record).
    //      Pins AuthController.authenticateUser:71-79.
    @Test
    void failedLogin_emitsLoginFailureSystemAuditRowWithNullOrgId() {
        String tag = shortUuid();
        String username = "up2-c2-actor-" + tag;

        // Register the user so the failure is "wrong password", not "nonexistent user".
        registerLogin(username, "real-pass-1234");

        // Attempt login with wrong password.
        ResponseEntity<Map<String, Object>> bad = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", "wrong-pass"), HttpHeaders.EMPTY),
                JSON_MAP);
        assertEquals(HttpStatus.UNAUTHORIZED, bad.getStatusCode());

        // AuthController sanitizes the attempted username out of LOGIN_FAILURE rows, recording the
        // fixed placeholder '<authentication-failed>' instead — an enumeration defense so the admin
        // audit-log filter can't be used to probe which usernames exist. The row is per-test isolated
        // by truncateDatabase(), so the placeholder match is unambiguous.
        Integer failureCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM system_audits
                WHERE username = '<authentication-failed>' AND action = 'LOGIN_FAILURE'
                  AND resource_type = 'AUTH' AND request_path = '/api/auth/login'
                  AND response_status = 401 AND org_id IS NULL
                """,
                Integer.class);
        assertEquals(1, failureCount,
                "failed login must emit exactly one sanitized LOGIN_FAILURE row (username placeholder, "
                        + "response_status=401, org_id=NULL)");
    }

    // C3 — Self-register with empty roles defaults to [ROLE_USER] only (minimal privilege).
    //      The default branch at AuthController.registerUser:137-140 used to also stamp
    //      ROLE_ADMIN, which meant any anonymous registrant on a publicly-reachable
    //      /register became an admin. fix/register-default-roles-no-admin tightened this.
    @Test
    void selfRegisterWithEmptyRoles_defaultsToRoleUserOnly_noAdmin() {
        String tag = shortUuid();
        String username = "up2-c3-default-roles-" + tag;
        String email = username + "@test.local";
        String password = "pass-c3-1234";

        // Register WITHOUT specifying roles. The registerLogin helper provides
        // List.of("ROLE_USER","ROLE_ADMIN") explicitly, so we must call /register directly.
        ResponseEntity<Map<String, Object>> reg = rest.exchange(
                url("/api/auth/register"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "email", email,
                        "password", password,
                        // Empty roles list — exercises the `strRoles.isEmpty()` branch.
                        "role", List.of()), HttpHeaders.EMPTY),
                JSON_MAP);
        assertEquals(HttpStatus.OK, reg.getStatusCode(), "/register returns 200 on success");

        // Log in to retrieve the JWT + roles via the auth response shape.
        ResponseEntity<Map<String, Object>> login = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", password), HttpHeaders.EMPTY),
                JSON_MAP);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) login.getBody().get("roles");
        assertEquals(1, roles.size(),
                "self-register with empty roles must default to exactly ONE role (minimal privilege); got: " + roles);
        assertTrue(roles.contains("ROLE_USER"),
                "default roles must include ROLE_USER; got: " + roles);
        assertTrue(!roles.contains("ROLE_ADMIN"),
                "default roles MUST NOT include ROLE_ADMIN — closes self-register-as-admin hole (fix/register-default-roles-no-admin)");
    }

    // ─── D: multi-tenant user CRUD behavior (pins AS-IS contract) ───

    // CONTEXT: UserAdminService.{updateUser, deleteUser, resetPassword} enforce per-tenant
    // scoping via requireSameOrg(target, callerOrgId). Cross-tenant attempts surface as
    // 404 (existence-leak protection — same pattern as ApprovalService). createUser stamps
    // the caller's orgId onto the new row so admin-created users land in the right tenant.

    // D1a — Admin-created user is stamped with the creator's orgId. createUser reads the
    //       caller's orgId from the SecurityContext (via CallerContext at the
    //       controller layer) and passes it through to user.setOrgId. Pre-fix the column
    //       was always NULL, leaving rows tenant-orphaned.
    @Test
    void adminCreatedUser_isStampedWithCreatorsOrgId() {
        String tag = shortUuid();
        String orgA = "org-up3-d1a-" + tag;
        HttpHeaders authA = registerLoginWithOrg("up3-d1a-admin-" + tag, orgA);

        String newUsername = "up3-d1a-victim-" + tag;
        UUID newId = createUserGetId(authA, newUsername, newUsername + "@test.local");

        String persistedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM users WHERE id = ?", String.class, newId);
        assertEquals(orgA, persistedOrgId,
                "admin-created user must inherit creator's orgId so the row lands in the right tenant");
    }

    // D1b — ADMIN in org-A CANNOT update a user in org-B. Cross-tenant attempt surfaces
    //       as 404 (existence-leak protection — same pattern as ApprovalService).
    @Test
    void adminInOrgA_cannotUpdateUserInOrgB_crossTenantReturns404() {
        String tag = shortUuid();
        String orgA = "org-up3-d1b-a-" + tag;
        String orgB = "org-up3-d1b-b-" + tag;
        HttpHeaders authA = registerLoginWithOrg("up3-d1b-admin-a-" + tag, orgA);

        String orgBUsername = "up3-d1b-victim-b-" + tag;
        registerLoginWithOrg(orgBUsername, orgB);
        UUID orgBUserId = UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, orgBUsername));
        String originalEmail = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, orgBUserId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/users/" + orgBUserId),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("email", "forged-" + tag + "@test.local"), authA),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant update must return 404 — existence-leak protection (requireSameOrg in UserAdminService)");
        assertEquals(originalEmail, jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, orgBUserId),
                "org-B user's email must be unchanged after rejected cross-tenant update");
    }

    // D1c — ADMIN in org-A CANNOT delete a user in org-B. Same requireSameOrg guard as D1b.
    @Test
    void adminInOrgA_cannotDeleteUserInOrgB_crossTenantReturns404() {
        String tag = shortUuid();
        String orgA = "org-up3-d1c-a-" + tag;
        String orgB = "org-up3-d1c-b-" + tag;
        HttpHeaders authA = registerLoginWithOrg("up3-d1c-admin-a-" + tag, orgA);

        String orgBUsername = "up3-d1c-victim-b-" + tag;
        registerLoginWithOrg(orgBUsername, orgB);
        UUID orgBUserId = UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, orgBUsername));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/users/" + orgBUserId),
                HttpMethod.DELETE,
                new HttpEntity<>(authA),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant delete must return 404 — same requireSameOrg guard as D1b");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, orgBUserId);
        assertEquals(1, count, "org-B user's row must persist — cross-tenant delete must be a no-op");
    }

    // ─── helpers ───

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-up1-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    /**
     * Registers + logs in a user with explicit ROLE_USER roles (no admin) using the
     * supplied password. Used by B1 (reset-password round-trip) and C1/C2 (audit emission).
     * Returns nothing — tests read the user id back via JDBC when needed.
     */
    private void registerLogin(String username, String password) {
        authenticateAs(username, username + "@test.local", password, List.of("ROLE_USER"));
    }

    /**
     * POSTs a new user via the admin API and returns the row's UUID. Used to seed fixtures
     * for the update/delete pins; fails the test (assert in caller) if creation doesn't
     * return 201.
     */
    @SuppressWarnings("unchecked")
    private UUID createUserGetId(HttpHeaders auth, String username, String email) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "email", email,
                        "password", "pass-1234",
                        "roles", List.of("ROLE_VIEWER")), auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "fixture: createUser via admin API must return 201; got " + resp.getStatusCode());
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
