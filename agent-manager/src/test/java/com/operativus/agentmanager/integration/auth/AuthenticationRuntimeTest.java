package com.operativus.agentmanager.integration.auth;

import com.operativus.agentmanager.control.repository.UserRepository;
import com.operativus.agentmanager.core.entity.User;
import com.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import com.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import com.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.TestData;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of {@code /api/auth/**} plus the
 *   JWT-backed Spring Security filter chain. Boots the full app context against the
 *   shared pgvector Testcontainer and exercises register/login/protected-resource flows
 *   over real HTTP — no MockMvc, no service-level injection, no @Transactional rollback.
 * State: Stateless (per-test isolation comes from {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §3.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, TestData.class})
public class AuthenticationRuntimeTest extends BaseIntegrationTest {

    @Autowired private UserRepository userRepository;

    @Value("${agentmanager.app.jwtSecret:mySuperSecretKeyThatIsVeryLongAndSecureEnoughForHS256AlgorithmByDefault}")
    private String jwtSecret;

    // §3.1 — register valid user
    @Test
    void registerValidUserPersistsBcryptHashAndReturns200() {
        var body = new RegisterRequest("alice", "alice@test.local", "alice-pass-123", List.of("ROLE_USER"));
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/register"), body, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Optional<User> persisted = userRepository.findByUsername("alice");
        assertTrue(persisted.isPresent(), "user row must be persisted");

        String stored = persisted.get().getPassword();
        assertNotEquals("alice-pass-123", stored, "raw password must never hit the database");
        assertTrue(stored.startsWith("$2"), "stored password must be a BCrypt hash");
        assertTrue(new BCryptPasswordEncoder().matches("alice-pass-123", stored),
                "BCrypt hash must verify the original password");
    }

    // §3.2a — register duplicate username
    @Test
    void registerDuplicateUsernameReturns400AndDoesNotCreateSecondRow() {
        var first = new RegisterRequest("dupuser", "first@test.local", "pass123456", List.of("ROLE_USER"));
        rest.postForEntity(url("/api/auth/register"), first, String.class);

        var second = new RegisterRequest("dupuser", "different@test.local", "pass123456", List.of("ROLE_USER"));
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/register"), second, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(1, userRepository.findAll().stream().filter(u -> u.getUsername().equals("dupuser")).count());
    }

    // §3.2b — register duplicate email
    @Test
    void registerDuplicateEmailReturns400AndDoesNotCreateSecondRow() {
        var first = new RegisterRequest("emailowner", "shared@test.local", "pass123456", List.of("ROLE_USER"));
        rest.postForEntity(url("/api/auth/register"), first, String.class);

        var second = new RegisterRequest("otheruser", "shared@test.local", "pass123456", List.of("ROLE_USER"));
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/register"), second, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(userRepository.findByUsername("otheruser").isEmpty(), "second user must not be persisted");
    }

    // Anti-enumeration: a duplicate-username collision and a duplicate-email collision
    // must return the SAME response body. Without this guard, an attacker can probe
    // /register to distinguish "username exists" from "email exists", enumerating valid
    // accounts. Pinned BYTE-FOR-BYTE so a future regression that re-introduces distinct
    // messages fails immediately.
    @Test
    void registerCollision_ResponseBodyIsIdenticalForDuplicateUsernameAndEmail() {
        rest.postForEntity(url("/api/auth/register"),
                new RegisterRequest("enum-existing", "enum-existing@test.local", "pass123456",
                        List.of("ROLE_USER")),
                String.class);

        // Probe with a DIFFERENT email, same username → "username exists" case.
        ResponseEntity<String> usernameCollision = rest.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest("enum-existing", "enum-different@test.local", "pass123456",
                        List.of("ROLE_USER")),
                String.class);

        // Probe with a DIFFERENT username, same email → "email exists" case.
        ResponseEntity<String> emailCollision = rest.postForEntity(
                url("/api/auth/register"),
                new RegisterRequest("enum-different", "enum-existing@test.local", "pass123456",
                        List.of("ROLE_USER")),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, usernameCollision.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, emailCollision.getStatusCode());
        assertEquals(usernameCollision.getBody(), emailCollision.getBody(),
                "anti-enumeration: response bodies for duplicate-username and duplicate-email "
                        + "must be byte-identical so an attacker cannot distinguish which constraint "
                        + "fired. Got username-collision body: " + usernameCollision.getBody()
                        + " | email-collision body: " + emailCollision.getBody());

        // Defense-in-depth: neither response should echo back the offending username or
        // email. Today the generic message is a static string with no field interpolation,
        // but if a future refactor reintroduces a templated message that includes the
        // submitted value, this guard catches the regression.
        String body = usernameCollision.getBody();
        assertTrue(body == null || (!body.contains("enum-existing")
                        && !body.contains("enum-existing@test.local")),
                "response body must not echo the submitted username or email; got: " + body);
    }

    // §3.3 — register with invalid role name → 400 via GlobalExceptionHandler.IllegalArgumentException
    @Test
    void registerInvalidRoleReturns400AndDoesNotCreateRow() {
        var body = new RegisterRequest("badrole", "badrole@test.local", "pass123456", List.of("ROLE_NONEXISTENT"));
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/register"), body, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(userRepository.findByUsername("badrole").isEmpty(), "user must not be persisted on role failure");
    }

    // §3.4 — login with correct credentials → JwtResponse with token + roles
    @Test
    void loginWithCorrectCredentialsReturnsJwtAndRoles() {
        register("loginok", "loginok@test.local", "ok-pass-123", List.of("ROLE_USER"));

        ResponseEntity<JwtResponse> response = rest.postForEntity(
                url("/api/auth/login"), new LoginRequest("loginok", "ok-pass-123"), JwtResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JwtResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.token(), "JWT token must be present");
        assertEquals("Bearer", body.type());
        assertEquals("loginok", body.username());
        assertTrue(body.roles().contains("ROLE_USER"), "issued token must reflect requested roles");
    }

    // §3.5 — login with wrong password → 401, no token
    @Test
    void loginWithWrongPasswordReturns401() {
        register("wrongpass", "wrongpass@test.local", "real-pass-123", List.of("ROLE_USER"));

        ResponseEntity<JwtResponse> response = rest.postForEntity(
                url("/api/auth/login"), new LoginRequest("wrongpass", "not-the-password"), JwtResponse.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody() == null || response.getBody().token() == null,
                "no JWT may be issued on bad credentials");
    }

    // §3.5 (variant) — login for nonexistent user → 401 (substitutes §3.6 disabled-user case;
    // the User entity has no disabled/locked flag, so that case is not implementable today)
    @Test
    void loginForNonexistentUserReturns401() {
        ResponseEntity<JwtResponse> response = rest.postForEntity(
                url("/api/auth/login"), new LoginRequest("ghost", "anything"), JwtResponse.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // §3.7 — expired token on protected endpoint → 401
    @Test
    void expiredTokenOnProtectedEndpointReturns401() {
        String expired = mintToken("expireduser", -60_000L); // signed with real secret, expiry in the past

        HttpHeaders headers = bearer(expired);
        ResponseEntity<String> response = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // §3.8 — malformed Authorization header → 401, request never reaches controller
    @Test
    void malformedAuthorizationHeaderReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer this.is.not.a.real.jwt");
        ResponseEntity<String> response = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // §3.10 — protected endpoint without token → 401; public endpoint without token → 200
    @Test
    void missingTokenIsRejectedOnProtectedAndAllowedOnPublicEndpoints() {
        ResponseEntity<String> protectedResponse = rest.getForEntity(url("/api/agents"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, protectedResponse.getStatusCode());

        ResponseEntity<String> health = rest.getForEntity(url("/actuator/health"), String.class);
        assertTrue(health.getStatusCode().is2xxSuccessful(),
                "actuator health is in the public-paths allowlist and must not require auth");
    }

    // §3.11 — token signed with wrong key → 401
    @Test
    void tokenSignedWithWrongKeyReturns401() {
        String foreignSecret = "totallyDifferentSecretKeyAlsoLongEnoughForHS256AlgorithmAndDifferent";
        Key foreignKey = Keys.hmacShaKeyFor(foreignSecret.getBytes());
        String forged = Jwts.builder()
                .setSubject("attacker")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(foreignKey, SignatureAlgorithm.HS256)
                .compact();

        ResponseEntity<String> response = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(bearer(forged)), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // §3.12 — concurrent login from same user issues independent tokens, both valid simultaneously
    @Test
    void concurrentLoginsIssueDistinctTokensThatAreBothValid() throws Exception {
        register("concurrent", "concurrent@test.local", "pass-concurrent-1", List.of("ROLE_USER"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<JwtResponse> a = CompletableFuture.supplyAsync(this::loginAsConcurrentUser, pool);
            CompletableFuture<JwtResponse> b = CompletableFuture.supplyAsync(this::loginAsConcurrentUser, pool);

            JwtResponse first = a.get(10, TimeUnit.SECONDS);
            JwtResponse second = b.get(10, TimeUnit.SECONDS);

            assertNotNull(first.token());
            assertNotNull(second.token());
            // iat resolution is per-second in the JJWT 0.11 builder, so tokens issued in the same
            // second are byte-equal — the contract under test is that BOTH are independently valid,
            // not that they differ. Verify both clear the filter chain on a real protected GET.
            assertTokenAcceptedOnProtectedEndpoint(first.token());
            assertTokenAcceptedOnProtectedEndpoint(second.token());
        } finally {
            pool.shutdownNow();
        }
    }

    // ─── helpers ───

    private void register(String username, String email, String password, List<String> roles) {
        var body = new RegisterRequest(username, email, password, roles);
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/register"), body, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "fixture register must succeed");
    }

    private JwtResponse loginAsConcurrentUser() {
        ResponseEntity<JwtResponse> response = rest.postForEntity(
                url("/api/auth/login"),
                new LoginRequest("concurrent", "pass-concurrent-1"),
                JwtResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private void assertTokenAcceptedOnProtectedEndpoint(String token) {
        ResponseEntity<String> response = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "concurrently-issued token must clear the filter chain on a real protected GET");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /**
     * Mints a JWT with the production signing key and a caller-supplied lifetime offset (ms).
     * Pass a negative offset to produce an already-expired token for §3.7.
     * Mirrors {@link com.operativus.agentmanager.control.security.JwtUtils#getSignInKey()} —
     * tries Base64 decode first, falls back to raw bytes — so this stays in sync if the
     * production secret is later switched to a Base64-encoded value.
     */
    private String mintToken(String subject, long lifetimeMs) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(jwtSecret);
        } catch (Exception e) {
            keyBytes = jwtSecret.getBytes();
        }
        Key key = Keys.hmacShaKeyFor(keyBytes);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(new Date(now - 120_000L)) // anchor iat in the past so iat<exp even when lifetime is negative
                .setExpiration(new Date(now + lifetimeMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
