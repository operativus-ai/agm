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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Fills two deferred gaps in user-admin runtime coverage
 *   identified after the UP1-UP3 + FIX1-FIX4 cycle:
 *   <ol>
 *     <li><b>DELETE FK cascade</b> — pins {@code deleteUser}'s behavior in the presence
 *         of dependent rows. Only {@code user_roles} has an {@code ON DELETE CASCADE}
 *         FK back to {@code users.id} (changeset 001-schema.sql:505). Other tables
 *         (audit_logs, agent_sessions, etc.) reference users by VARCHAR username, not
 *         FK to UUID — those become app-level orphans after delete. Pinning the
 *         current behavior so a future "soft delete" or "anonymize" refactor surfaces
 *         visibly.</li>
 *     <li><b>bulkCreate mixed-input rollback matrix</b> — pins the transactional
 *         semantics of {@code @Transactional bulkCreate}. Mixed batches with username
 *         already-exists rows return per-item statuses without rolling back; but a
 *         mid-batch email-collision throw rolls back the entire batch (no
 *         partially-created users). The existing {@code UserAdminRuntimeTest} pins
 *         the idempotency-cache replay path; the existing {@code UserAdminCrudRuntimeTest}
 *         pins per-item missing-id 404s. This class fills the gap between them.</li>
 *   </ol>
 *
 *   <p>Both gaps came out of the cross-cutting findings scan at the end of the
 *   UP1-UP3 / FIX1-FIX4 ship cycle and are tracked in {@code 05-current-work.md}
 *   §"Next-action options for future session".
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class UserAdminDeleteCascadeAndBulkRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── D1 — DELETE cascades user_roles row(s) ──────────────────────────────

    @Test
    void deleteUser_cascadesUserRolesRow_perOnDeleteCascadeFk() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("d1-admin-" + tag);

        // Seed a victim with at least one role row (registerLogin already grants ROLE_USER).
        String victim = "d1-victim-" + tag;
        registerLogin(victim, "pwd-d1-1234");
        UUID victimId = userIdOf(victim);
        Integer rolesBefore = countUserRolesFor(victimId);
        assertTrue(rolesBefore != null && rolesBefore >= 1,
                "fixture must seed at least one user_roles row; got " + rolesBefore);

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/users/" + victimId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        assertAll("DELETE cascades user_roles via ON DELETE CASCADE",
                () -> assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                        "delete must return 204"),
                () -> assertEquals(0, countUserRowsFor(victimId).intValue(),
                        "users row must be deleted"),
                () -> assertEquals(0, countUserRolesFor(victimId).intValue(),
                        "user_roles rows for the deleted user MUST be cascaded (fk_user_roles_user "
                                + "ON DELETE CASCADE); orphan role rows here would mean the FK "
                                + "was loosened or the schema regressed"));
    }

    // ─── D2 — DELETE does NOT cascade VARCHAR-username refs (audit_logs etc) ─

    @Test
    void deleteUser_doesNotTouchAuditLogsOrSessionsReferencedByVarcharUsername() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("d2-admin-" + tag);

        String victim = "d2-victim-" + tag;
        registerLogin(victim, "pwd-d2-1234");
        UUID victimId = userIdOf(victim);

        // Seed a row in a table that references the username as a non-FK VARCHAR. Use
        // agent_sessions.user_id (VARCHAR, no FK to users.id). The seed approximates
        // the production state where the user has a session footprint at delete time.
        String sessionId = "sess-d2-" + tag + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, ?, 'org-d2-orphan-test', now(), now())
                """, sessionId, victim);

        Integer sessionsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_sessions WHERE user_id = ?",
                Integer.class, victim);
        assertEquals(1, sessionsBefore, "fixture: one orphan-able session seeded");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/users/" + victimId),
                HttpMethod.DELETE,
                new HttpEntity<>(auth),
                Void.class);

        assertAll("DELETE leaves app-level dependents as orphans",
                () -> assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                        "delete must succeed even when user has dependents — there are no FK "
                                + "constraints blocking it (only user_roles has FK to users.id)"),
                () -> assertEquals(0, countUserRowsFor(victimId).intValue(),
                        "users row deleted"),
                () -> assertEquals(1, jdbc.queryForObject(
                                "SELECT COUNT(*) FROM agent_sessions WHERE user_id = ?",
                                Integer.class, victim).intValue(),
                        "agent_sessions row referencing the deleted username remains — it is "
                                + "now an app-level orphan. Pinning this AS-IS so a future "
                                + "decision to cascade-anonymize sessions surfaces visibly."));
    }

    // ─── B1 — bulk with mixed new + already-exists returns per-item statuses ─

    @Test
    void bulkCreate_mixedNewAndExisting_returnsPerItemStatuses_andCommitsNewRows() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("b1-admin-" + tag);

        // Pre-seed one user that will collide on the second item by username.
        String existingUser = "b1-existing-" + tag;
        registerLogin(existingUser, "pwd-b1-existing-1234");

        String newUser = "b1-new-" + tag;
        Map<String, Object> body = Map.of("users", List.of(
                Map.of("username", newUser,
                       "email", newUser + "@test.local",
                       "password", "pwd-b1-new-1234",
                       "roles", List.of("ROLE_USER")),
                Map.of("username", existingUser,
                       "email", existingUser + "@test.local",
                       "password", "pwd-b1-existing-1234",
                       "roles", List.of("ROLE_USER"))));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("mixed-input bulk returns per-item statuses without rollback",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "200 (not 201) — bulk returns ResponseEntity.ok per controller"),
                () -> assertEquals(1, ((Number) resp.getBody().get("created")).intValue(),
                        "exactly one row created (the new username)"),
                () -> assertEquals(1, ((Number) resp.getBody().get("alreadyExisted")).intValue(),
                        "exactly one row classified as already_exists"),
                () -> assertTrue(userExists(newUser),
                        "new user must be persisted — username collision on a different row "
                                + "does NOT rollback the new row (per-item semantics, not "
                                + "batch-level rollback)"),
                () -> assertTrue(userExists(existingUser),
                        "pre-existing user must be intact"));
    }

    // ─── B2 — mid-batch email collision against existing user → BVE + 400 + full rollback ─

    @Test
    void bulkCreate_emailCollisionAgainstExistingUser_throwsBVE_andRollsBackEntireBatch() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("b2-admin-" + tag);

        // Pre-seed a user whose email will collide with item 2 in the batch.
        String existingUser = "b2-existing-" + tag;
        String collidingEmail = "b2-collision-" + tag + "@test.local";
        registerLoginWithEmail(existingUser, collidingEmail, "pwd-b2-existing-1234");

        String survivor = "b2-survivor-" + tag;
        String victim = "b2-victim-" + tag;

        // Item 1: brand-new user (would commit if the batch succeeded).
        // Item 2: new username but email collides with existingUser → BVE on the second iter.
        Map<String, Object> body = Map.of("users", List.of(
                Map.of("username", survivor,
                       "email", survivor + "@test.local",
                       "password", "pwd-b2-survivor-1234",
                       "roles", List.of("ROLE_USER")),
                Map.of("username", victim,
                       "email", collidingEmail,
                       "password", "pwd-b2-victim-1234",
                       "roles", List.of("ROLE_USER"))));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("email collision rolls back the entire batch (transactional)",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "BVE -> 400 via GlobalExceptionHandler"),
                () -> assertNotNull(resp.getBody().get("detail"),
                        "ProblemDetail must carry a detail message identifying the conflict"),
                () -> assertTrue(((String) resp.getBody().get("detail")).contains(collidingEmail),
                        "detail message MUST name the colliding email for triage; got: "
                                + resp.getBody().get("detail")),
                () -> assertEquals(false, userExists(survivor),
                        "the first batch item (survivor) MUST be rolled back too — "
                                + "@Transactional rollback on BVE throws away the prior insert; "
                                + "a survivor row here would mean rollback semantics regressed"),
                () -> assertEquals(false, userExists(victim),
                        "victim row was never inserted (BVE thrown before save)"),
                () -> assertTrue(userExists(existingUser),
                        "pre-existing user must be untouched"));
    }

    // ─── B3 — empty batch returns 200 with empty items ───────────────────────

    @Test
    void bulkCreate_emptyBatch_returns200_withZeroCreatedAndZeroAlreadyExisted() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("b3-admin-" + tag);

        Map<String, Object> body = Map.of("users", List.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("empty batch is a 200 no-op",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "empty bulk is not a validation error — service iterates zero times"),
                () -> assertEquals(0, ((Number) resp.getBody().get("created")).intValue()),
                () -> assertEquals(0, ((Number) resp.getBody().get("alreadyExisted")).intValue()),
                () -> assertTrue(((List<?>) resp.getBody().get("items")).isEmpty(),
                        "items list must be empty"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pwd-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    /**
     * Register a basic user (ROLE_USER) and stamp them into DEFAULT_SYSTEM_ORG so the
     * admin (also in DEFAULT_SYSTEM_ORG via {@link #adminHeaders}) can act on them
     * post-{@code requireSameOrg} guard (PR #743). Without the org stamp, the admin
     * sees the victim as cross-tenant and the DELETE returns 404.
     */
    private void registerLogin(String username, String password) {
        registerLoginWithEmail(username, username + "@test.local", password);
    }

    private void registerLoginWithEmail(String username, String email, String password) {
        rest.postForEntity(url("/api/auth/register"),
                Map.of("username", username, "email", email, "password", password),
                Void.class);
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ? AND org_id IS NULL",
                "DEFAULT_SYSTEM_ORG", username);
    }

    private UUID userIdOf(String username) {
        return UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, username));
    }

    private Integer countUserRowsFor(UUID userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
    }

    private Integer countUserRolesFor(UUID userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE user_id = ?", Integer.class, userId);
    }

    private boolean userExists(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
