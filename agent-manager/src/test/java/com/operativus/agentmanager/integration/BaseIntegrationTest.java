package com.operativus.agentmanager.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import com.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import com.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpCacheConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Base class for true black-box integration tests. Boots the real Spring
 *   context against a pgvector Testcontainer, exposes a TestRestTemplate that hits the app over
 *   HTTP on a random port, and resets persisted state between tests.
 * State: Stateless (shared singleton Postgres container across the JVM).
 *
 * Conventions:
 *   - Tagged "integration" so `./mvnw test` skips it; `./mvnw test -Dgroups=integration` runs it.
 *   - Container is started once per JVM (static init) — DO NOT annotate with @Testcontainers /
 *     @Container, that would make it per-class and add ~20s of container boot per test class.
 *   - Liquibase runs on first boot against the container and schema survives between tests;
 *     {@link #truncateDatabase()} wipes row state only, so we don't pay for re-migration.
 *   - We cannot rely on @Transactional rollback here: RunExecutionManager, PersistentJobQueueService,
 *     AgentStreamManager (reactive), and @Async/@Scheduled paths commit on their own threads.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
// The four Fake* test doubles are imported at the BASE so every integration test shares ONE
// merged context configuration instead of fragmenting the Spring TestContext cache by each
// subclass's @Import permutation. Spring's cache key is the deduped union of all @Import on the
// class hierarchy, so a subclass listing any subset of these (or none) now resolves to the same
// cached context — collapsing ~280 classes off the prior 68-context churn that drove eviction/
// shutdown stalls. All four are additive/inert where unused: FakeChatModel/FakeEmbedding shadow
// the (test-profile-excluded) provider model beans, FakeModelProvider registers an opt-in "FAKE"
// provider key, and NoOpReflection stubs only the post-run @Async reflection hot-path (no test
// asserts on agent_reflections persistence). Subclasses that need EXTRA doubles (JobQueueTestSupport,
// SchedulerTestSupport, …) keep only those in their own @Import.
@Import({NoOpCacheConfig.class, FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public abstract class BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BaseIntegrationTest.class);

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("agent_manager_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "max_connections=300")
            .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        registry.add("spring.liquibase.user", POSTGRES::getUsername);
        registry.add("spring.liquibase.password", POSTGRES::getPassword);
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Registers a user via the real /api/auth endpoints and returns a Bearer-authenticated
     * HttpHeaders object. Use this instead of fabricating tokens — it exercises the real
     * JwtTokenProvider + security filter chain, which is the whole point of black-box tests.
     *
     * <p>Stamps {@code org_id = DEFAULT_SYSTEM_ORG} on the registered user IFF
     * {@code AuthController.registerUser} did not set one (it currently doesn't for
     * self-registered users). This is what production-bound endpoints expect — without
     * an org claim in the JWT, {@code AgentContextHolder.getOrgId()} returns {@code null}
     * downstream and any service that short-circuits on a null orgId (e.g.
     * {@code BudgetPolicyService.findActiveCeiling}) becomes a no-op, masking real
     * test breakage.
     *
     * <p>The JDBC update runs BEFORE login so the issued JWT carries the {@code org_id}
     * claim (per {@code JwtUtils.generateJwtToken}, which only adds the claim when the
     * principal's {@code orgId} is non-blank at issue time).
     *
     * <p>Use {@link #registerLoginWithOrg(String, String)} for tenant-isolation tests
     * that need a specific (non-default) org id.
     */
    protected HttpHeaders authenticateAs(String username, String email, String password, List<String> roles) {
        var register = new RegisterRequest(username, email, password, roles);
        rest.postForEntity(url("/api/auth/register"), register, Void.class);

        // Defensive: if AuthController.registerUser starts stamping org_id at some point,
        // do not overwrite it. We only fill in DEFAULT_SYSTEM_ORG when null.
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ? AND org_id IS NULL",
                "DEFAULT_SYSTEM_ORG", username);

        var login = new LoginRequest(username, password);
        ResponseEntity<JwtResponse> response = rest.postForEntity(url("/api/auth/login"), login, JwtResponse.class);
        String token = response.getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /**
     * Registers a ROLE_USER + ROLE_ADMIN user, force-binds them to the given org via JDBC,
     * and logs them in so the issued JWT carries the new {@code org_id} claim. Returns
     * Bearer-auth headers ready for cross-tenant runtime tests.
     *
     * <p>The JDBC update is the canonical fixture pattern for tenant-isolation tests because
     * the {@code POST /api/auth/register} flow stamps the caller's own org_id onto the new
     * user; there is no admin endpoint to register a user into an arbitrary org. Tenant
     * context is propagated via {@link com.operativus.agentmanager.control.security.TenantContextFilter}
     * reading the JWT claim into {@code AgentContextHolder}.
     *
     * <p>Replaces ≥8 inline copies that previously lived in tenant-isolation runtime tests.
     */
    protected HttpHeaders registerLoginWithOrg(String username, String orgId) {
        var register = new RegisterRequest(username, username + "@test.local",
                "pass-iso-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
        rest.postForEntity(url("/api/auth/register"), register, Void.class);
        jdbc.update("UPDATE users SET org_id = ? WHERE username = ?", orgId, username);
        var login = new LoginRequest(username, "pass-iso-1234");
        ResponseEntity<JwtResponse> response = rest.postForEntity(url("/api/auth/login"),
                login, JwtResponse.class);
        String token = response.getBody().token();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    protected <T> ResponseEntity<T> authorizedGet(String path, HttpHeaders auth, Class<T> type) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(auth), type);
    }

    protected <T> ResponseEntity<T> authorizedPost(String path, Object body, HttpHeaders auth, Class<T> type) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, auth), type);
    }

    private static final MappingJackson2HttpMessageConverter STATUS_ONLY_JSON_WRITER =
            new MappingJackson2HttpMessageConverter();

    /**
     * POSTs a JSON body and returns ONLY the HTTP status — the response body is never read.
     *
     * <p>Use for endpoints that return {@code text/event-stream} (an {@code SseEmitter}): a blocking
     * read of the chunked SSE body can throw {@code ConnectionClosedException: Premature end of chunk}
     * when the server completes the emitter with an error (e.g. the A2A failure paths — unknown agent,
     * LLM throws, cross-tenant reject). The status line is available before any body read, and these
     * tests assert the task outcome via the persisted {@code a2a_task_events} audit rows, not the
     * stream body. Works equally for non-streaming responses (e.g. a 400 validation error), so it can
     * replace a {@code exchange(..., String.class)} call uniformly.
     */
    protected HttpStatusCode postForStatusNoBody(String path, Object body, HttpHeaders headers) {
        return rest.getRestTemplate().execute(
                url(path),
                HttpMethod.POST,
                request -> {
                    request.getHeaders().putAll(headers);
                    STATUS_ONLY_JSON_WRITER.write(body, MediaType.APPLICATION_JSON, request);
                },
                response -> response.getStatusCode());
    }

    /**
     * POSTs a JSON body to a {@code text/event-stream} endpoint and returns whatever body bytes
     * arrived as a String, tolerating an abrupt chunk close. Use when a test must assert on the SSE
     * content (e.g. proving no cross-tenant leak) but the server completes the emitter with an error,
     * which closes the chunked stream without a terminal chunk — a strict read would throw before the
     * caller sees the (already-received) events.
     */
    protected String postAndReadEventStreamTolerant(String path, Object body, HttpHeaders headers) {
        return rest.getRestTemplate().execute(
                url(path),
                HttpMethod.POST,
                request -> {
                    request.getHeaders().putAll(headers);
                    STATUS_ONLY_JSON_WRITER.write(body, MediaType.APPLICATION_JSON, request);
                },
                response -> {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream in = response.getBody()) {
                        in.transferTo(buf);
                    } catch (java.io.IOException ignored) {
                        // Premature chunk close on the SSE error path — return what arrived.
                    }
                    return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
                });
    }

    /**
     * Truncates every table in the public schema except Liquibase bookkeeping, in one statement.
     * RESTART IDENTITY resets sequences so surrogate keys are deterministic across tests.
     * CASCADE sidesteps FK ordering.
     *
     * <p>Wrapped in a deadlock-aware retry loop because TRUNCATE acquires AccessExclusiveLock
     * on every listed table and the Spring context still has live background services committing
     * concurrently from their own connections (RunExecutionManager, ScheduleExecutionPoller,
     * BackgroundJobQueue, ModelAvailabilityPoller — see class-level javadoc). When their
     * row-lock acquisition order intersects the TRUNCATE's table-lock acquisition order
     * Postgres detects and aborts one side as a deadlock loser. PG's recommended remedy is
     * a transparent retry; one backoff is almost always enough because the racing transaction
     * has already released its locks by the time we wake.
     */
    @AfterEach
    protected void truncateDatabase() {
        List<String> tables = jdbc.queryForList(
                """
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename NOT LIKE 'databasechangelog%'
                """,
                String.class);

        if (tables.isEmpty()) return;

        String joined = tables.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(", "));
        String truncate = "TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE";

        int maxAttempts = 3;
        long backoffMs = 100L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                jdbc.execute(truncate);
                return;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    throw e;
                }
                log.warn("truncateDatabase: PG deadlock on attempt {}/{} — retrying after {}ms backoff",
                        attempt, maxAttempts, backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during truncateDatabase retry backoff", ie);
                }
                backoffMs *= 2;
            }
        }
    }
}
