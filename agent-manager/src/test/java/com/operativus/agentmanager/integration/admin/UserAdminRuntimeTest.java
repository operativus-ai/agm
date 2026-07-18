package com.operativus.agentmanager.integration.admin;

import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import com.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the {@code /api/admin/users} surface —
 *   {@link com.operativus.agentmanager.control.controller.UserAdminController} →
 *   {@link com.operativus.agentmanager.control.service.UserAdminService}. Pins the paginated
 *   list response shape, role-promotion-on-next-login, delete-removes-row, and the current
 *   shape of three aspirational invariants the matrix names but the code does not yet
 *   enforce (admin-only RBAC, login-blocked-when-disabled, bulk-create, audit trail).
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §23 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T045.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link com.operativus.agentmanager.control.controller.UserAdminController} has NO
 *     method-level security; {@link com.operativus.agentmanager.control.config.SecurityConfig}
 *     falls through to {@code anyRequest().authenticated()} for {@code /api/admin/users/**}.
 *     Matrix §23 case 2 ("non-admin → 403") is aspirational — case (b) below pins the CURRENT
 *     shape (ROLE_USER caller receives 200 + full listing). A future RBAC landing flips this
 *     pin on purpose. The IDEALIZED case 2 pin stays {@code @Disabled} with the exact flip
 *     instructions.
 *   - {@link com.operativus.agentmanager.control.security.UserDetailsImpl#isEnabled()} always
 *     returns {@code true}, ignoring {@link User#isDisabled()}. So setting {@code disabled=true}
 *     via the admin API does NOT block subsequent {@code POST /api/auth/login} calls. Matrix
 *     §23 case 4 ("login disabled") is aspirational — case (e) pins that login CURRENTLY
 *     succeeds on a disabled user. Case (d) confirms the benign half of the same case:
 *     existing Bearer tokens remain valid (no revocation on disable today, which is also the
 *     stated acceptable behavior "until expiry").
 *   - {@link com.operativus.agentmanager.control.controller.UserAdminController} has no bulk
 *     endpoint. Matrix §23 case 6 (bulk-create idempotent on re-submit) is pinned
 *     {@code @Disabled} until a bulk surface lands.
 *   - {@link com.operativus.agentmanager.control.service.UserAdminService} does not invoke
 *     {@link com.operativus.agentmanager.control.service.AuditLogService}. Matrix §23 case 7
 *     (admin-action audit trail) is pinned {@code @Disabled} until the service-layer hook is
 *     added.
 *   - JWT role claims are populated from {@link User#getRoles()} at login time by the
 *     authentication provider — there's no token-rebuild mechanism, so a role edit made
 *     through {@code PUT /api/admin/users/{id}} is only visible on the NEXT login. Case (c)
 *     exercises both sides: (1) an existing token issued pre-promotion still carries the old
 *     claim set, and (2) re-login after the promotion yields a JwtResponse with the new role.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class UserAdminRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // Redis container used by the §23 case 6 bulk-create idempotency tests below to back the
    // {@code Idempotency-Key} header replay cache. Started statically so the whole suite
    // shares one Redis instance (cheaper than per-test spin-up). Properties are registered via
    // {@link DynamicPropertySource} so the Spring AOT-style context picks up a real host/port
    // instead of the default.
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private UserRepository userRepository;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // §23 — Case (a): GET /api/admin/users returns a Spring Data Page<UserAdminDTO>.
    // Admin caller, two seeded users (plus the admin themselves = 3 expected rows).
    // Pins the Page-shape response the UI consumes. A switch to an unwrapped list would
    // break the "content" array navigation — that change should be deliberate.
    @Test
    void listUsersAsAdminReturnsPagedUserAdminDtos() {
        HttpHeaders adminAuth = adminHeaders("user-admin-lister");

        // Create two additional users through the admin surface so the listing has real rows.
        createUserViaAdminApi(adminAuth, "alice-list", "alice-list@test.local", "pass-admin-list-1", "ROLE_USER");
        createUserViaAdminApi(adminAuth, "bob-list",   "bob-list@test.local",   "pass-admin-list-2", "ROLE_OPERATOR");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users"), HttpMethod.GET, new HttpEntity<>(adminAuth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertNotNull(content, "paginated response must carry a 'content' array");
        assertTrue(content.size() >= 3,
                "expected at least 3 users (admin caller + 2 seeded); got " + content.size());
        assertTrue(content.stream().anyMatch(u -> "alice-list".equals(u.get("username"))),
                "seeded user 'alice-list' must appear in the listing");
        assertTrue(content.stream().anyMatch(u -> "bob-list".equals(u.get("username"))),
                "seeded user 'bob-list' must appear in the listing");
    }

    // §23 — Case 2: RBAC invariant — ROLE_USER must not reach /api/admin/users.
    // UserAdminController carries a class-level @PreAuthorize("hasRole('ADMIN')"), so any
    // caller without ROLE_ADMIN hits Spring Security's default AccessDeniedHandler → 403.
    @Test
    void nonAdminListingShouldBeRejected403() {
        HttpHeaders userAuth = authenticateAs("user-admin-nonadmin-ideal",
                "user-admin-nonadmin-ideal@test.local", "pass-user-admin-1", List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users"), HttpMethod.GET, new HttpEntity<>(userAuth), JSON_MAP);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER must not reach /api/admin/users — 403 is the @PreAuthorize failure shape. "
                        + "A 200 here would mean the class-level @PreAuthorize is missing or @EnableMethodSecurity is off.");
    }

    // §23 — Case (c): Promote a ROLE_USER to ROLE_ADMIN via PUT /api/admin/users/{id}.
    // Pins the "effective on next login" contract: (1) the pre-promotion token's role
    // claims are frozen at issue time (no live role rebuild), (2) a fresh login after the
    // promotion yields a JwtResponse carrying ROLE_ADMIN.
    @Test
    void promoteRoleIsEffectiveOnNextLogin() {
        HttpHeaders adminAuth = adminHeaders("user-admin-promoter");

        // Register a ROLE_USER via the public /api/auth path — that way we also get a token
        // issued pre-promotion that we can compare against the post-promotion token.
        String username = "promotee-" + shortUuid();
        authenticateAs(username, username + "@test.local", "pass-promote-1", List.of("ROLE_USER"));

        User promotee = userRepository.findByUsername(username)
                .orElseThrow(() -> new AssertionError("freshly-registered user must exist in the repo"));

        // Pre-promotion login — claim set should be ROLE_USER only.
        JwtResponse preLogin = loginForJwt(username, "pass-promote-1");
        assertTrue(preLogin.roles().contains("ROLE_USER"),
                "pre-promotion JwtResponse.roles must carry ROLE_USER");
        assertFalse(preLogin.roles().contains("ROLE_ADMIN"),
                "pre-promotion JwtResponse.roles must NOT carry ROLE_ADMIN");

        // Promote via the admin API.
        Map<String, Object> update = Map.of(
                "email", promotee.getEmail(),
                "roles", List.of("ROLE_ADMIN", "ROLE_USER"),
                "disabled", false
        );
        ResponseEntity<Map<String, Object>> putResp = rest.exchange(
                url("/api/admin/users/" + promotee.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(update, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, putResp.getStatusCode(), "admin PUT must update the role set");

        // Post-promotion login — claim set now includes ROLE_ADMIN.
        JwtResponse postLogin = loginForJwt(username, "pass-promote-1");
        assertTrue(postLogin.roles().contains("ROLE_ADMIN"),
                "post-promotion JwtResponse.roles must carry ROLE_ADMIN — promotion was not effective on next login");

        // Repo row reflects the merged role set too.
        User refreshed = userRepository.findByUsername(username).orElseThrow();
        assertTrue(refreshed.getRoles().toString().contains("ROLE_ADMIN"),
                "User.roles must include ROLE_ADMIN after the admin update");
    }

    // §23 — Case (d): Matrix case 4 says disabling a user "existing tokens still work
    // until expiry". That's the benign half: nothing in the request path re-checks the
    // disabled flag on each JWT-authenticated call, so an old Bearer token remains valid.
    // This test pins that invariant explicitly. The FAILING half (case 4b "login disabled")
    // is pinned in {@link #disabledUserLoginCurrentlySucceeds_isEnabledGapPin}.
    @Test
    void disabledUserExistingBearerTokenStillWorks() {
        HttpHeaders adminAuth = adminHeaders("user-admin-disabler");

        String username = "disable-me-" + shortUuid();
        HttpHeaders userAuth = authenticateAs(username, username + "@test.local",
                "pass-disable-1", List.of("ROLE_USER"));

        User target = userRepository.findByUsername(username).orElseThrow();

        // Smoke the token against /api/auth/me (or any authenticated endpoint) before disable.
        ResponseEntity<String> preDisable = rest.exchange(
                url("/api/auth/me"), HttpMethod.GET, new HttpEntity<>(userAuth), String.class);
        // Any 2xx or 404 (endpoint missing) is acceptable — we only need to confirm no 401.
        assertFalse(preDisable.getStatusCode().value() == 401,
                "pre-disable token must authenticate; got " + preDisable.getStatusCode());

        // Disable the user via the admin surface.
        Map<String, Object> update = Map.of(
                "email", target.getEmail(),
                "roles", List.of("ROLE_USER"),
                "disabled", true
        );
        ResponseEntity<Map<String, Object>> putResp = rest.exchange(
                url("/api/admin/users/" + target.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(update, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, putResp.getStatusCode(), "disable PUT must succeed");

        User reloaded = userRepository.findByUsername(username).orElseThrow();
        assertTrue(reloaded.isDisabled(), "disabled flag must be persisted on the user row");

        // Same old token should still hit the same endpoint — nothing in the request path
        // today re-checks the disabled flag for an already-issued JWT.
        ResponseEntity<String> postDisable = rest.exchange(
                url("/api/auth/me"), HttpMethod.GET, new HttpEntity<>(userAuth), String.class);
        assertFalse(postDisable.getStatusCode().value() == 401,
                "existing Bearer token must remain valid after admin disable; got " + postDisable.getStatusCode());
    }

    // §23 — Case 4b: Admin toggles disabled=true → login must reject.
    // UserDetailsImpl#isEnabled() now returns !user.isDisabled(), so the
    // DaoAuthenticationProvider surfaces DisabledException → AuthenticationException →
    // AuthController translates to 401 (same path as bad credentials).
    @Test
    void disabledUserShouldNotBeAbleToLogIn() {
        HttpHeaders adminAuth = adminHeaders("user-admin-disabler-login");

        String username = "login-disabled-" + shortUuid();
        authenticateAs(username, username + "@test.local", "pass-disable-login-1", List.of("ROLE_USER"));

        User target = userRepository.findByUsername(username).orElseThrow();

        Map<String, Object> update = Map.of(
                "email", target.getEmail(),
                "roles", List.of("ROLE_USER"),
                "disabled", true
        );
        rest.exchange(url("/api/admin/users/" + target.getId()),
                HttpMethod.PUT, new HttpEntity<>(update, adminAuth), JSON_MAP);

        assertTrue(userRepository.findByUsername(username).orElseThrow().isDisabled(),
                "precondition: user row must be flagged disabled");

        var loginBody = new LoginRequest(username, "pass-disable-login-1");
        ResponseEntity<JwtResponse> loginResp = rest.postForEntity(
                url("/api/auth/login"), loginBody, JwtResponse.class);

        assertEquals(HttpStatus.UNAUTHORIZED, loginResp.getStatusCode(),
                "disabled user must not be able to authenticate — 401 is the AuthController shape for a failed login. "
                        + "A 200 here means UserDetailsImpl#isEnabled() is no longer consulting User.isDisabled().");
    }

    // §23 — Case (f): DELETE /api/admin/users/{id} removes the row (current implementation
    // is a hard delete via {@link com.operativus.agentmanager.control.service.UserAdminService#deleteUser},
    // not a soft delete — the matrix case 5 allows either "soft-delete or cascade per spec").
    @Test
    void deleteUserRemovesRow() {
        HttpHeaders adminAuth = adminHeaders("user-admin-deleter");

        String username = "delete-me-" + shortUuid();
        authenticateAs(username, username + "@test.local", "pass-delete-1", List.of("ROLE_USER"));

        UUID targetId = userRepository.findByUsername(username).orElseThrow().getId();
        assertTrue(userRepository.findById(targetId).isPresent(), "precondition: target must exist");

        ResponseEntity<Void> delResp = rest.exchange(
                url("/api/admin/users/" + targetId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, delResp.getStatusCode(),
                "admin DELETE must return 204 on success");

        Optional<User> after = userRepository.findById(targetId);
        assertTrue(after.isEmpty(),
                "target user row must be removed from the users table after admin delete");
    }

    // §23 — Case 6 (matrix): Bulk-create users is idempotent on re-submit. Two layers are
    // exercised below: (1) per-item idempotency — re-running the same payload without an
    // Idempotency-Key still converges because duplicate usernames come back as
    // {@code already_exists}, and (2) Idempotency-Key header replay — the response body is
    // returned verbatim from Redis on resubmit. The endpoint is POST /api/admin/users/bulk
    // (see {@link com.operativus.agentmanager.control.controller.UserAdminController#bulkCreate}).
    @Test
    void bulkCreateUsersIdempotentOnResubmit() {
        HttpHeaders adminAuth = adminHeaders("bulk-create-admin");

        Map<String, Object> u1 = Map.of(
                "username", "bulk-new-alpha",
                "email", "bulk-new-alpha@test.local",
                "password", "pass-bulk-alpha-1",
                "roles", List.of("ROLE_USER"));
        Map<String, Object> u2 = Map.of(
                "username", "bulk-new-beta",
                "email", "bulk-new-beta@test.local",
                "password", "pass-bulk-beta-1",
                "roles", List.of("ROLE_USER"));
        Map<String, Object> body = Map.of("users", List.of(u1, u2));

        HttpHeaders first = new HttpHeaders();
        first.addAll(adminAuth);
        first.set("Idempotency-Key", "bulk-create-key-001");

        ResponseEntity<Map<String, Object>> firstResp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, first),
                JSON_MAP);
        assertEquals(HttpStatus.OK, firstResp.getStatusCode(),
                "first bulk submission must return 200 OK");
        Map<String, Object> firstBody = firstResp.getBody();
        assertNotNull(firstBody, "first bulk response body must not be null");
        assertEquals(2, ((Number) firstBody.get("created")).intValue(),
                "both rows are new on first submit");
        assertEquals(0, ((Number) firstBody.get("alreadyExisted")).intValue(),
                "no pre-existing rows on first submit");

        assertTrue(userRepository.existsByUsername("bulk-new-alpha"),
                "alpha must be in users table after first submit");
        assertTrue(userRepository.existsByUsername("bulk-new-beta"),
                "beta must be in users table after first submit");

        HttpHeaders second = new HttpHeaders();
        second.addAll(adminAuth);
        second.set("Idempotency-Key", "bulk-create-key-001");

        ResponseEntity<Map<String, Object>> secondResp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, second),
                JSON_MAP);
        assertEquals(HttpStatus.OK, secondResp.getStatusCode(),
                "resubmit with same Idempotency-Key must return 200 OK, not 409");
        Map<String, Object> secondBody = secondResp.getBody();
        assertNotNull(secondBody);
        assertEquals(firstBody, secondBody,
                "resubmit with same Idempotency-Key must replay the exact same response body");

        long alphaCount = userRepository.findAll().stream()
                .filter(u -> "bulk-new-alpha".equals(u.getUsername())).count();
        long betaCount = userRepository.findAll().stream()
                .filter(u -> "bulk-new-beta".equals(u.getUsername())).count();
        assertEquals(1L, alphaCount, "bulk-new-alpha must not be duplicated");
        assertEquals(1L, betaCount, "bulk-new-beta must not be duplicated");
    }

    // §23 — Case 6 (matrix): Bulk-create is also idempotent WITHOUT an Idempotency-Key header
    // — per-item convergence means a resubmit of an identical payload marks each row as
    // {@code already_exists} instead of throwing a unique-constraint violation.
    @Test
    void bulkCreateWithoutIdempotencyHeaderIsStillPerItemIdempotent() {
        HttpHeaders adminAuth = adminHeaders("bulk-no-header-admin");

        Map<String, Object> only = Map.of(
                "username", "bulk-lone-user",
                "email", "bulk-lone-user@test.local",
                "password", "pass-bulk-lone-1",
                "roles", List.of("ROLE_USER"));
        Map<String, Object> body = Map.of("users", List.of(only));

        ResponseEntity<Map<String, Object>> firstResp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, firstResp.getStatusCode());
        assertEquals(1, ((Number) firstResp.getBody().get("created")).intValue());

        // Resubmit without Idempotency-Key — falls back to content-hash replay. Same body
        // means same hash means same response.
        ResponseEntity<Map<String, Object>> secondResp = rest.exchange(
                url("/api/admin/users/bulk"),
                HttpMethod.POST,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, secondResp.getStatusCode(),
                "content-hash replay must yield 200 OK on resubmit");
        // The replay path returns the cached body verbatim, so {@code created=1} persists on
        // resubmit. If the cache expired mid-flight the service-layer per-item check would
        // still prevent a duplicate — that branch is exercised by
        // {@link #bulkCreateConvergesWhenCacheMisses} below.
        assertEquals(firstResp.getBody(), secondResp.getBody(),
                "identical payload must produce the identical response via content-hash replay");

        long count = userRepository.findAll().stream()
                .filter(u -> "bulk-lone-user".equals(u.getUsername())).count();
        assertEquals(1L, count, "single user must not be duplicated on resubmit");
    }

    // Direct service-layer probe for the per-item convergence — the controller's Redis replay
    // shortcuts the resubmit path, but we also want to confirm the service itself is safe to
    // re-invoke with the same input. Uses a distinct idempotency key on the second call so we
    // bypass the cache.
    @Test
    void bulkCreateConvergesWhenCacheMisses() {
        HttpHeaders adminAuth = adminHeaders("bulk-cache-miss-admin");

        Map<String, Object> body = Map.of("users", List.of(Map.of(
                "username", "bulk-converge-user",
                "email", "bulk-converge-user@test.local",
                "password", "pass-bulk-converge-1",
                "roles", List.of("ROLE_USER"))));

        HttpHeaders firstHeaders = new HttpHeaders();
        firstHeaders.addAll(adminAuth);
        firstHeaders.set("Idempotency-Key", "converge-key-A");

        rest.exchange(url("/api/admin/users/bulk"), HttpMethod.POST,
                new HttpEntity<>(body, firstHeaders), JSON_MAP);

        HttpHeaders secondHeaders = new HttpHeaders();
        secondHeaders.addAll(adminAuth);
        secondHeaders.set("Idempotency-Key", "converge-key-B");

        ResponseEntity<Map<String, Object>> second = rest.exchange(
                url("/api/admin/users/bulk"), HttpMethod.POST,
                new HttpEntity<>(body, secondHeaders), JSON_MAP);
        assertEquals(HttpStatus.OK, second.getStatusCode(),
                "cache miss + service-layer per-item idempotency must still return 200 OK");
        assertEquals(0, ((Number) second.getBody().get("created")).intValue(),
                "resubmit under a new key must create zero new users");
        assertEquals(1, ((Number) second.getBody().get("alreadyExisted")).intValue(),
                "resubmit must report the pre-existing user as already_exists");
    }

    // §23 — Case 7 (matrix-ideal): Admin mutations (create/update/delete) must produce a row
    // in AuditLogService. Pinned @Disabled: UserAdminService does not inject or invoke
    // AuditLogService today, so admin actions leave no audit trail beyond the service-layer
    // log.info() statements. Flip this test out of @Disabled when the service-layer hook
    // into AuditLogService is added.
    @Test
    @Disabled("§23 case 7 — UserAdminService does not invoke AuditLogService.")
    void adminActionsProduceAuditTrailRows() {
        // Intentionally empty until AuditLogService wiring lands on UserAdminService.
    }

    // ─── helpers ───

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-user-admin-1",
                List.of("ROLE_ADMIN", "ROLE_USER"));
    }

    private JwtResponse loginForJwt(String username, String password) {
        ResponseEntity<JwtResponse> resp = rest.postForEntity(
                url("/api/auth/login"), new LoginRequest(username, password), JwtResponse.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "login must succeed for '" + username + "'");
        assertNotNull(resp.getBody(), "login must return a JwtResponse body");
        return resp.getBody();
    }

    private void createUserViaAdminApi(HttpHeaders adminAuth, String username, String email,
                                       String password, String role) {
        Map<String, Object> body = Map.of(
                "username", username,
                "email", email,
                "password", password,
                "roles", Set.of(role)
        );
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/users"), HttpMethod.POST, new HttpEntity<>(body, adminAuth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "admin POST /api/admin/users must return 201 for new user '" + username + "'; got " + resp.getStatusCode() + " body=" + resp.getBody());
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
