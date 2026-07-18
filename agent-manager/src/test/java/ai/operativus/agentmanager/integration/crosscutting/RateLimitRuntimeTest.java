package ai.operativus.agentmanager.integration.crosscutting;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage for the T050a / matrix §27.2 rate-limit
 *   surface. Pins {@link ai.operativus.agentmanager.control.security.RateLimitingFilter}:
 *   - per-user token-bucket enforcement (Resilience4j), HTTP 429 with Retry-After on exhaustion.
 *   - buckets are keyed by authenticated principal name, so different users have independent caps.
 *   - unauthenticated requests (pre-JWT) bypass the limiter.
 *   - {@code shouldNotFilter} skips everything outside {@code /api/**} (actuator, docs).
 * State: Stateless (per-test DB truncation, but Resilience4j's {@code RateLimiterRegistry} is a
 *   singleton that retains per-user limiters for the JVM lifetime — we avoid cross-test
 *   contamination by minting fresh random usernames per case).
 *
 * Knob: {@code app.rate-limit.requests-per-second=3} is pinned at the class level (prod default
 *   is 50). Three is low enough to exhaust in a sub-second loop, high enough that the register +
 *   login traffic from {@link BaseIntegrationTest#authenticateAs} does not count (those hit
 *   {@code /api/auth/**} as the anonymous principal; the filter bypasses that path).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T050 §27.2.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
@TestPropertySource(properties = {
        "app.rate-limit.requests-per-second=1",
        "app.rate-limit.timeout-millis=0",
        "app.rate-limit.max-tracked-users=5"
})
public class RateLimitRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    // §27.2 case 1 — burst past the cap returns 429 with Retry-After.
    // Implementation note: Resilience4j's {@code limitRefreshPeriod} is hardcoded to 1s inside
    // the filter. TestRestTemplate round-trips take ~200–400ms each over loopback HTTP, so a
    // sequential loop of N requests takes > N*200ms and will straddle the refresh boundary.
    // To reliably exhaust the 1-permit-per-second bucket we fire CONCURRENTLY via a virtual-
    // thread pool: all requests hit the filter before any permits refresh, so only 1 gets a
    // permit and the rest return 429. Asserts ≥1 rate-limited (and ≥1 success) plus the
    // documented Retry-After + problem+json contract.
    @Test
    void burstBeyondCapReturns429WithRetryAfter() throws Exception {
        String user = "rl-burst-" + shortUuid();
        HttpHeaders auth = authenticateAs(user, user + "@test.local",
                "pass-rl-1234", List.of("ROLE_USER"));

        final int BURST_SIZE = 20;
        List<ResponseEntity<String>> responses = fireConcurrent(BURST_SIZE, auth);

        long rateLimited = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS).count();
        long successful = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK).count();

        assertTrue(rateLimited >= 1,
                "at least 1 of " + BURST_SIZE + " concurrent requests must return 429; got "
                        + rateLimited + " — RateLimitingFilter is not enforcing the configured cap");
        assertTrue(successful >= 1,
                "at least 1 of " + BURST_SIZE + " concurrent requests must succeed; got "
                        + successful + " — the filter is denying everything, which indicates a misconfig");

        ResponseEntity<String> observed429 = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                .findFirst().orElseThrow();
        assertEquals("1", observed429.getHeaders().getFirst("Retry-After"),
                "429 response must carry Retry-After: 1 header (RateLimitingFilter line 84)");
        String body = observed429.getBody();
        assertNotNull(body, "429 must carry a problem+json body");
        assertTrue(body.contains("\"status\":429") && body.contains("Too Many Requests"),
                "429 body must match the documented problem+json shape. Got: " + body);
    }

    // §27.2 case 2 — buckets are keyed by principal name, not global. User A exhausting their
    // cap must not affect user B's. Proves the registry key is {@code "user:" + auth.getName()}
    // (RateLimitingFilter line 70).
    @Test
    void perUserBucketsAreIndependent() throws Exception {
        String userA = "rl-indep-a-" + shortUuid();
        String userB = "rl-indep-b-" + shortUuid();
        HttpHeaders authA = authenticateAs(userA, userA + "@test.local",
                "pass-rl-1234", List.of("ROLE_USER"));
        HttpHeaders authB = authenticateAs(userB, userB + "@test.local",
                "pass-rl-1234", List.of("ROLE_USER"));

        // Drain user A's bucket with a concurrent burst (see burstBeyondCap for why sequential
        // doesn't work). Precondition: at least 1 of A's requests must be rate-limited.
        List<ResponseEntity<String>> aResponses = fireConcurrent(20, authA);
        long aRateLimited = aResponses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS).count();
        assertTrue(aRateLimited >= 1,
                "precondition: user A's concurrent burst must have triggered ≥1 429; got " + aRateLimited);

        // Fire user B's burst next. B must see ≥1 successful 200 (the bucket starts full on
        // first access). Any non-429 response (200/4xx/5xx other than 429) counts as "not
        // rate-limited". If B is IMMEDIATELY 429-rate-limited on every request, the limiter
        // key is global instead of per-principal.
        List<ResponseEntity<String>> bResponses = fireConcurrent(20, authB);
        long bRateLimited = bResponses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS).count();
        long bSuccess = bResponses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        assertTrue(bSuccess >= 1,
                "user B must see ≥1 successful request even while user A is throttled; "
                        + "got success=" + bSuccess + " rateLimited=" + bRateLimited
                        + ". Zero successes would indicate the RateLimiter is keyed globally "
                        + "instead of per-principal (expected key 'user:' + auth.getName()).");
    }

    // §27.2 case 3 — unauthenticated POST /api/auth/login is now per-IP rate-limited
    // (default 10/min/IP via app.rate-limit.login.requests-per-minute) to prevent
    // audit-table DoS from LOGIN_FAILURE row floods. Iterations 1-5 succeed under the
    // limit; the 11th-iteration failure path is exercised in
    // LoginRateLimitRuntimeTest.eleventhLoginAttemptInWindowReturns429.
    @Test
    void unauthenticatedLoginUnderLimitDoesNotReturn429() {
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> resp = rest.exchange(
                    url("/api/auth/login"),
                    HttpMethod.POST,
                    new HttpEntity<>("{\"username\":\"nobody\",\"password\":\"nope\"}", jsonHeaders()),
                    String.class);
            assertTrue(resp.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS,
                    "unauthenticated POST /api/auth/login iteration #" + (i + 1) + " returned 429 — "
                            + "5 attempts must be under the 10/min/IP login rate-limit");
        }
    }

    // §27.2 case 4 — shouldNotFilter short-circuits for non-/api paths (actuator, docs, static).
    // /actuator/health should be spammable without 429. This pins the path guard in
    // RateLimitingFilter.shouldNotFilter (line 53-57).
    @Test
    void nonApiPathsSkipRateLimitFilter() {
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> resp = rest.exchange(
                    url("/actuator/health"), HttpMethod.GET, HttpEntity.EMPTY, String.class);
            assertTrue(resp.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS,
                    "/actuator/health iteration #" + (i + 1) + " returned 429 — "
                            + "shouldNotFilter must skip non-/api paths");
        }
    }

    // Matrix §27.2 — bucket registry eviction. {@link RateLimitingFilter} wraps the
    // {@link RateLimiterRegistry} in a bounded LRU cache keyed by "user:" + principal name;
    // on overflow, the eldest access-order entry is evicted from both the internal cache AND
    // the Resilience4j registry (via {@code registry.remove(name)}). This test pins that
    // contract: firing N distinct authenticated principals through the filter, where
    // N > app.rate-limit.max-tracked-users, must leave the registry's growth bounded by
    // max-tracked-users — not unbounded at N.
    //
    // Class-level {@code app.rate-limit.max-tracked-users=5} makes the assertion tight.
    @Test
    void rateLimiterRegistryShouldEvictStaleUserBuckets() throws Exception {
        int maxTracked = 5;
        int distinctUsers = 20;

        int initialSize = rateLimiterRegistry.getAllRateLimiters().size();

        for (int i = 0; i < distinctUsers; i++) {
            String user = "rl-evict-" + i + "-" + shortUuid();
            HttpHeaders auth = authenticateAs(user, user + "@test.local",
                    "pass-rl-evict-1234", List.of("ROLE_USER"));
            rest.exchange(url("/api/config/templates"), HttpMethod.GET,
                    new HttpEntity<>(auth), String.class);
        }

        int finalSize = rateLimiterRegistry.getAllRateLimiters().size();
        int growth = finalSize - initialSize;
        assertTrue(growth <= maxTracked,
                "RateLimiterRegistry grew by " + growth + " after " + distinctUsers
                        + " distinct authenticated principals hit the filter. Expected ≤ "
                        + maxTracked + " (app.rate-limit.max-tracked-users). Unbounded "
                        + "growth means the LRU eviction in RateLimitingFilter is broken.");
    }

    // ─── helpers ───

    /**
     * Fires {@code count} GET requests to /api/config/templates CONCURRENTLY with the given
     * auth headers, using a virtual-thread executor so all requests hit Tomcat before any
     * permits can refresh. Returns the raw response list.
     */
    private List<ResponseEntity<String>> fireConcurrent(int count, HttpHeaders auth) throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<ResponseEntity<String>>> futures = new java.util.ArrayList<>();
            AtomicInteger started = new AtomicInteger();
            for (int i = 0; i < count; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    started.incrementAndGet();
                    return rest.exchange(
                            url("/api/config/templates"),
                            HttpMethod.GET,
                            new HttpEntity<>(auth),
                            String.class);
                }, pool));
            }
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdown();
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return h;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
