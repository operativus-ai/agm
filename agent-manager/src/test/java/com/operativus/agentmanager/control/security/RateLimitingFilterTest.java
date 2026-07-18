package com.operativus.agentmanager.control.security;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class RateLimitingFilterTest {

    private RateLimitingFilter filter;
    private RateLimiterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = RateLimiterRegistry.ofDefaults();
        // Allow only 2 requests per second for testing; use a large LRU cap so eviction
        // doesn't interfere with these unit-test cases.
        filter = new RateLimitingFilter(registry, 2, 0, 10_000, 10,
                /* registerRequestsPerMinute = */ 5,
                /* dispatchRequestsPerMinute = */ 60, new SimpleMeterRegistry());
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestsUnderLimit() throws Exception {
        setAuthentication("user-1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    /**
     * Login rate-limit: unauthenticated POST /api/auth/login is rate-limited per IP
     * (default 10/min/IP) to defeat audit-table DoS via LOGIN_FAILURE floods. Uses a
     * test-local filter instance with limit=1/min so a single extra attempt trips 429.
     */
    @Test
    void loginRateLimitReturns429AfterCapExceededForSameIp() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitingFilter loginFilter = new RateLimitingFilter(
                RateLimiterRegistry.ofDefaults(),
                /* requestsPerSecond = */ 50,
                /* timeoutMillis = */ 0,
                /* maxTrackedUsers = */ 10_000,
                /* loginRequestsPerMinute = */ 1,
                /* registerRequestsPerMinute = */ 60,
                /* dispatchRequestsPerMinute = */ 60,
                new SimpleMeterRegistry());

        // 1st POST /api/auth/login from 1.2.3.4 — consumes the single permit.
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/auth/login");
        req1.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        loginFilter.doFilterInternal(req1, resp1, new MockFilterChain());
        assertNotEquals(429, resp1.getStatus(),
                "1st login attempt must pass; got " + resp1.getStatus());

        // 2nd POST /api/auth/login from same IP — exceeds cap, 429.
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/auth/login");
        req2.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        loginFilter.doFilterInternal(req2, resp2, new MockFilterChain());
        assertEquals(429, resp2.getStatus(),
                "2nd login attempt within window must return 429; got " + resp2.getStatus());
        assertEquals("60", resp2.getHeader("Retry-After"),
                "Retry-After must be 60 (refresh period in seconds)");
        assertTrue(resp2.getContentAsString().contains("Login rate limit"),
                "429 body must carry the user-visible detail; got: "
                        + resp2.getContentAsString());
    }

    @Test
    void loginRateLimitBucketsAreSeparatePerIp() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitingFilter loginFilter = new RateLimitingFilter(
                RateLimiterRegistry.ofDefaults(),
                50, 0, 10_000, /* loginRequestsPerMinute = */ 1,
                /* registerRequestsPerMinute = */ 60,
                /* dispatchRequestsPerMinute = */ 60,
                new SimpleMeterRegistry());

        // IP-A consumes its permit.
        MockHttpServletRequest reqA = new MockHttpServletRequest("POST", "/api/auth/login");
        reqA.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse respA = new MockHttpServletResponse();
        loginFilter.doFilterInternal(reqA, respA, new MockFilterChain());
        assertNotEquals(429, respA.getStatus());

        // IP-B has its own bucket — must still pass.
        MockHttpServletRequest reqB = new MockHttpServletRequest("POST", "/api/auth/login");
        reqB.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse respB = new MockHttpServletResponse();
        loginFilter.doFilterInternal(reqB, respB, new MockFilterChain());
        assertNotEquals(429, respB.getStatus(),
                "different IP must have an independent bucket; got " + respB.getStatus());

        // IP-A again — same IP, exceeds cap, 429.
        MockHttpServletRequest reqA2 = new MockHttpServletRequest("POST", "/api/auth/login");
        reqA2.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse respA2 = new MockHttpServletResponse();
        loginFilter.doFilterInternal(reqA2, respA2, new MockFilterChain());
        assertEquals(429, respA2.getStatus(),
                "same IP exceeding its bucket must 429 even when other IPs are fresh");
    }

    @Test
    void loginRateLimitOnlyAppliesToPostLogin() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitingFilter loginFilter = new RateLimitingFilter(
                RateLimiterRegistry.ofDefaults(),
                50, 0, 10_000, /* loginRequestsPerMinute = */ 1,
                /* registerRequestsPerMinute = */ 60,
                /* dispatchRequestsPerMinute = */ 60,
                new SimpleMeterRegistry());

        // GET /api/auth/login (or any non-POST) — bypass the login rate-limiter.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/login");
            req.setRemoteAddr("1.2.3.4");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            loginFilter.doFilterInternal(req, resp, new MockFilterChain());
            assertNotEquals(429, resp.getStatus(),
                    "GET must not be rate-limited by the POST-login bucket; iteration "
                            + i + " got " + resp.getStatus());
        }

        // POST /api/auth/register — different path, not subject to login bucket.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/register");
            req.setRemoteAddr("1.2.3.4");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            loginFilter.doFilterInternal(req, resp, new MockFilterChain());
            assertNotEquals(429, resp.getStatus(),
                    "POST /register must not be rate-limited by the login bucket; iteration "
                            + i + " got " + resp.getStatus());
        }
    }

    /**
     * Register rate-limit: unauthenticated POST /api/auth/register is rate-limited per IP
     * (default 5/min/IP — tighter than login because each register writes user + audit rows).
     * Both endpoints are in the permitAll allowlist; without this bucket an attacker can
     * spam /register to create unbounded garbage users and flood the audit table. PR #1023
     * collapsed the response body to defeat body-based enumeration; this limiter bounds the
     * attack rate.
     */
    @Test
    void registerRateLimitReturns429AfterCapExceededForSameIp() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitingFilter registerFilter = new RateLimitingFilter(
                RateLimiterRegistry.ofDefaults(),
                /* requestsPerSecond = */ 50,
                /* timeoutMillis = */ 0,
                /* maxTrackedUsers = */ 10_000,
                /* loginRequestsPerMinute = */ 60,
                /* registerRequestsPerMinute = */ 1,
                /* dispatchRequestsPerMinute = */ 60,
                new SimpleMeterRegistry());

        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/auth/register");
        req1.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        registerFilter.doFilterInternal(req1, resp1, new MockFilterChain());
        assertNotEquals(429, resp1.getStatus(),
                "1st register attempt must pass; got " + resp1.getStatus());

        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/auth/register");
        req2.setRemoteAddr("1.2.3.4");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        registerFilter.doFilterInternal(req2, resp2, new MockFilterChain());
        assertEquals(429, resp2.getStatus(),
                "2nd register attempt within window must return 429; got " + resp2.getStatus());
        assertEquals("60", resp2.getHeader("Retry-After"),
                "Retry-After must be 60 (refresh period in seconds)");
        assertTrue(resp2.getContentAsString().contains("Register rate limit"),
                "429 body must carry the user-visible detail; got: "
                        + resp2.getContentAsString());
    }

    @Test
    void registerRateLimitBucketsAreSeparatePerIp() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitingFilter registerFilter = new RateLimitingFilter(
                RateLimiterRegistry.ofDefaults(),
                50, 0, 10_000, /* loginRequestsPerMinute = */ 60,
                /* registerRequestsPerMinute = */ 1,
                /* dispatchRequestsPerMinute = */ 60,
                new SimpleMeterRegistry());

        MockHttpServletRequest reqA = new MockHttpServletRequest("POST", "/api/auth/register");
        reqA.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse respA = new MockHttpServletResponse();
        registerFilter.doFilterInternal(reqA, respA, new MockFilterChain());
        assertNotEquals(429, respA.getStatus());

        MockHttpServletRequest reqB = new MockHttpServletRequest("POST", "/api/auth/register");
        reqB.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse respB = new MockHttpServletResponse();
        registerFilter.doFilterInternal(reqB, respB, new MockFilterChain());
        assertNotEquals(429, respB.getStatus(),
                "different IP must have an independent bucket; got " + respB.getStatus());

        MockHttpServletRequest reqA2 = new MockHttpServletRequest("POST", "/api/auth/register");
        reqA2.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse respA2 = new MockHttpServletResponse();
        registerFilter.doFilterInternal(reqA2, respA2, new MockFilterChain());
        assertEquals(429, respA2.getStatus(),
                "same IP exceeding its bucket must 429 even when other IPs are fresh");
    }

    @Test
    void registerRateLimitOnlyAppliesToPostRegister() throws Exception {
        SecurityContextHolder.clearContext();
        RateLimitingFilter registerFilter = new RateLimitingFilter(
                RateLimiterRegistry.ofDefaults(),
                50, 0, 10_000, /* loginRequestsPerMinute = */ 60,
                /* registerRequestsPerMinute = */ 1,
                /* dispatchRequestsPerMinute = */ 60,
                new SimpleMeterRegistry());

        // GET /api/auth/register (or any non-POST) — bypass the register rate-limiter.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/register");
            req.setRemoteAddr("1.2.3.4");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            registerFilter.doFilterInternal(req, resp, new MockFilterChain());
            assertNotEquals(429, resp.getStatus(),
                    "GET must not be rate-limited by the POST-register bucket; iteration "
                            + i + " got " + resp.getStatus());
        }

        // POST /api/auth/login — different path, not subject to register bucket.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
            req.setRemoteAddr("1.2.3.4");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            registerFilter.doFilterInternal(req, resp, new MockFilterChain());
            assertNotEquals(429, resp.getStatus(),
                    "POST /login must not be rate-limited by the register bucket; iteration "
                            + i + " got " + resp.getStatus());
        }
    }

    @Test
    void returns429WhenLimitExceeded() throws Exception {
        setAuthentication("user-burst");

        // Exhaust the limit (2 requests)
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(req, resp, new MockFilterChain());
        }

        // Third request should be rate-limited
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("Rate limit exceeded"));
    }

    @Test
    void skipsNonApiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void skipsUnauthenticatedRequests() throws Exception {
        // No authentication set
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // Should pass through without rate limiting
        assertEquals(200, response.getStatus());
    }

    @Test
    void isolatesLimitsPerUser() throws Exception {
        // User A exhausts limit
        setAuthentication("user-a");
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/agents");
            filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        // User B should still be allowed
        setAuthentication("user-b");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void incrementsEvictionCounterWhenLruCapExceeded() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        // maxTrackedUsers=2 → admitting a 3rd distinct principal must evict the eldest.
        RateLimitingFilter filterWithTinyCap = new RateLimitingFilter(
                RateLimiterRegistry.ofDefaults(), 50, 0, 2, 10,
                /* registerRequestsPerMinute = */ 5,
                /* dispatchRequestsPerMinute = */ 60, meterRegistry);

        for (String user : new String[]{"user-1", "user-2", "user-3"}) {
            setAuthentication(user);
            filterWithTinyCap.doFilterInternal(
                    new MockHttpServletRequest("GET", "/api/agents"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        double evictions = meterRegistry.counter(RateLimitingFilter.EVICTION_METRIC_NAME).count();
        assertEquals(1.0, evictions,
                "Admitting a third principal under maxTrackedUsers=2 must evict exactly one entry.");
    }

    private void setAuthentication(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList()));
    }
}
