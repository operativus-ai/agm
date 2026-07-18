package ai.operativus.agentmanager.integration.admin;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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
 * Domain Responsibility: Pins {@code UserAdminService.bulkCreate} collision-detection
 *   behavior for collisions that happen WITHIN a single batch (not against pre-existing
 *   users). Companion to {@link UserAdminDeleteCascadeAndBulkRuntimeTest} which pinned
 *   collisions against an already-persisted user.
 *
 *   <p>Within-batch collision matrix:
 *   <ul>
 *     <li><b>Same email twice in the batch</b> — JPA flush before the second
 *         {@code existsByEmail} check surfaces the in-flight insert; BVE thrown, entire
 *         batch rolls back (no users created).</li>
 *     <li><b>Same username + same email twice in the batch</b> — first save commits the
 *         new row; second iteration's {@code findByUsername} sees the just-persisted row
 *         AND the email matches, so the row is classified as {@code already_exists}.
 *         No throw — the matrix §23 case 6 idempotency contract.</li>
 *     <li><b>Username collision against a pre-existing row with a different email</b>
 *         — the "username exists with different email than resubmitted" branch throws
 *         BVE. With @Transactional, all prior batch rows roll back.</li>
 *   </ul>
 *
 *   <p>Pinning this is the only way to distinguish a future refactor that switches
 *   from per-item BVE-on-throw to "skip-this-item, continue batch" semantics — the
 *   current contract is "abort the whole batch on email collision, even within-batch".
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class UserAdminBulkWithinBatchCollisionRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── WB1 — same email twice in batch -> BVE + full rollback ─────────────

    @Test
    void bulkCreate_sameEmailTwiceInBatch_throwsBVE_andRollsBackBothRows() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wb1-" + tag);

        String sharedEmail = "wb1-shared-" + tag + "@test.local";
        String firstUser = "wb1-first-" + tag;
        String secondUser = "wb1-second-" + tag;

        Map<String, Object> body = Map.of("users", List.of(
                Map.of("username", firstUser,
                       "email", sharedEmail,
                       "password", "pwd-wb1-1234",
                       "roles", List.of("ROLE_USER")),
                Map.of("username", secondUser,
                       "email", sharedEmail,
                       "password", "pwd-wb1-1234",
                       "roles", List.of("ROLE_USER"))));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("within-batch duplicate email -> BVE + full rollback",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "BVE -> 400 via GlobalExceptionHandler"),
                () -> assertNotNull(resp.getBody().get("detail")),
                () -> assertTrue(((String) resp.getBody().get("detail")).contains(sharedEmail),
                        "detail must name the colliding email; got: " + resp.getBody().get("detail")),
                () -> assertEquals(false, userExists(firstUser),
                        "first user (whose insert was flushed before the second's existsByEmail "
                                + "check) must be ROLLED BACK along with the second — @Transactional "
                                + "semantics; a survivor here would indicate the rollback regressed"),
                () -> assertEquals(false, userExists(secondUser),
                        "second user must not exist — BVE thrown before save"));
    }

    // ─── WB2 — same username + same email twice -> already_exists on second ────

    @Test
    void bulkCreate_sameUsernameAndEmailTwiceInBatch_secondIsAlreadyExists_firstPersists() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wb2-" + tag);

        String sharedUsername = "wb2-shared-" + tag;
        String sharedEmail = "wb2-shared-" + tag + "@test.local";

        Map<String, Object> body = Map.of("users", List.of(
                Map.of("username", sharedUsername,
                       "email", sharedEmail,
                       "password", "pwd-wb2-1234",
                       "roles", List.of("ROLE_USER")),
                Map.of("username", sharedUsername,
                       "email", sharedEmail,
                       "password", "pwd-wb2-1234",
                       "roles", List.of("ROLE_USER"))));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("within-batch full idempotency -> per-item already_exists, no throw",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "200 (not 400) — same username AND same email is the idempotent "
                                + "resubmit path"),
                () -> assertEquals(1, ((Number) resp.getBody().get("created")).intValue(),
                        "first iteration creates the user"),
                () -> assertEquals(1, ((Number) resp.getBody().get("alreadyExisted")).intValue(),
                        "second iteration's findByUsername sees the just-flushed row, the "
                                + "email matches, so it is classified as already_exists"),
                () -> assertTrue(userExists(sharedUsername),
                        "the user must exist — first iteration's save committed"));
    }

    // ─── WB2b — same username + DIFFERENT email twice -> BVE + full rollback ────

    @Test
    void bulkCreate_sameUsernameButDifferentEmailsTwiceInBatch_throwsBVE_andRollsBackBoth() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wb2b-" + tag);

        String sharedUsername = "wb2b-shared-" + tag;
        String firstEmail = "wb2b-first-" + tag + "@test.local";
        String secondEmail = "wb2b-second-" + tag + "@test.local";

        Map<String, Object> body = Map.of("users", List.of(
                Map.of("username", sharedUsername,
                       "email", firstEmail,
                       "password", "pwd-wb2b-1234",
                       "roles", List.of("ROLE_USER")),
                Map.of("username", sharedUsername,
                       "email", secondEmail,
                       "password", "pwd-wb2b-1234",
                       "roles", List.of("ROLE_USER"))));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("same-username + different-emails within batch -> BVE + rollback",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "second iteration sees first's flushed row but emails differ — the "
                                + "'different email than resubmitted' branch throws BVE"),
                () -> assertTrue(((String) resp.getBody().get("detail")).contains(sharedUsername),
                        "detail must name the conflicting username; got: "
                                + resp.getBody().get("detail")),
                () -> assertEquals(false, userExists(sharedUsername),
                        "first iteration's row must be rolled back too — @Transactional"));
    }

    // ─── WB3 — same-username conflict where existing email differs -> BVE rollback ──

    @Test
    void bulkCreate_sameUsernameButDifferentExistingEmail_throwsBVE_andRollsBackBatch() {
        String tag = shortUuid();
        HttpHeaders auth = adminHeaders("wb3-" + tag);

        // Pre-seed a user that the batch's second item will collide with by username,
        // but its email is different from the resubmitted row — bulkCreate's "different
        // email than the resubmitted row" branch throws BVE.
        String victimUsername = "wb3-victim-" + tag;
        String existingEmail = "wb3-existing-" + tag + "@test.local";
        String resubmittedEmail = "wb3-resubmitted-" + tag + "@test.local";
        registerLoginWithEmail(victimUsername, existingEmail, "pwd-wb3-1234");

        String survivor = "wb3-survivor-" + tag;
        Map<String, Object> body = Map.of("users", List.of(
                Map.of("username", survivor,
                       "email", survivor + "@test.local",
                       "password", "pwd-wb3-1234",
                       "roles", List.of("ROLE_USER")),
                Map.of("username", victimUsername,
                       "email", resubmittedEmail,  // different from existing
                       "password", "pwd-wb3-1234",
                       "roles", List.of("ROLE_USER"))));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("username conflict + different existing email -> BVE + full rollback",
                () -> assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                        "BVE -> 400 because the existing user has a different email"),
                () -> assertNotNull(resp.getBody().get("detail")),
                () -> assertTrue(((String) resp.getBody().get("detail")).toLowerCase().contains(victimUsername.toLowerCase()),
                        "detail must name the conflicting username; got: " + resp.getBody().get("detail")),
                () -> assertEquals(false, userExists(survivor),
                        "survivor row must be rolled back too — same @Transactional semantics "
                                + "as WB1"),
                () -> assertTrue(userExists(victimUsername),
                        "pre-existing user must be untouched"),
                () -> assertEquals(existingEmail, jdbc.queryForObject(
                                "SELECT email FROM users WHERE username = ?", String.class, victimUsername),
                        "existing user's email must remain the original — failed batch must NOT "
                                + "have updated it to the resubmitted email"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pwd-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private void registerLoginWithEmail(String username, String email, String password) {
        rest.postForEntity(url("/api/auth/register"),
                Map.of("username", username, "email", email, "password", password),
                Void.class);
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ? AND org_id IS NULL",
                "DEFAULT_SYSTEM_ORG", username);
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
