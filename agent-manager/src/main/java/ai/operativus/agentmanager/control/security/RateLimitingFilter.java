package ai.operativus.agentmanager.control.security;

import ai.operativus.agentmanager.core.model.SecurityPrincipals;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global API rate limiting filter that enforces per-user request throttling.
 * Runs after authentication filters so the user identity is available.
 *
 * Rate limits are configurable via properties:
 * - app.rate-limit.requests-per-second: max requests per second per user (default: 50)
 * - app.rate-limit.timeout-millis: how long to wait for a permit (default: 0 = fail immediately)
 * - app.rate-limit.max-tracked-users: bound on distinct principals held in RateLimiterRegistry
 *   (default: 10000). When exceeded, the least-recently-used principal's limiter is evicted
 *   from both the internal access-order cache and the Resilience4j registry.
 *
 * Unauthenticated requests and public paths are not rate-limited (they are handled before auth).
 * Returns HTTP 429 Too Many Requests with Retry-After header when limit is exceeded.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    /** Public meter name pinned for downstream dashboards. Drift breaks alerts.
     *  Counter increments by 1 every time a least-recently-used principal's bucket is
     *  evicted from the in-JVM cache because {@code maxTrackedUsers} was exceeded.
     *  Operators alert on a sustained non-zero rate of evictions — it signals either a
     *  user-base-size mismatch with the cap or a deliberate cache-thrash attack. */
    public static final String EVICTION_METRIC_NAME = "agm.rate_limit.http.evicted_principals";

    private final RateLimiterRegistry rateLimiterRegistry;
    private final int requestsPerSecond;
    private final long timeoutMillis;
    private final int loginRequestsPerMinute;
    private final int registerRequestsPerMinute;
    private final int dispatchRequestsPerMinute;
    private final Map<String, Boolean> accessOrderKeys;
    private final Counter evictionCounter;

    public RateLimitingFilter(
            RateLimiterRegistry rateLimiterRegistry,
            @Value("${app.rate-limit.requests-per-second:50}") int requestsPerSecond,
            @Value("${app.rate-limit.timeout-millis:0}") long timeoutMillis,
            @Value("${app.rate-limit.max-tracked-users:10000}") int maxTrackedUsers,
            @Value("${app.rate-limit.login.requests-per-minute:10}") int loginRequestsPerMinute,
            @Value("${app.rate-limit.register.requests-per-minute:5}") int registerRequestsPerMinute,
            @Value("${app.rate-limit.dispatch.requests-per-minute:60}") int dispatchRequestsPerMinute,
            MeterRegistry meterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.requestsPerSecond = requestsPerSecond;
        this.timeoutMillis = timeoutMillis;
        this.loginRequestsPerMinute = loginRequestsPerMinute;
        this.registerRequestsPerMinute = registerRequestsPerMinute;
        this.dispatchRequestsPerMinute = dispatchRequestsPerMinute;
        this.evictionCounter = Counter.builder(EVICTION_METRIC_NAME)
                .description("Count of LRU principal evictions from the per-user RateLimiter cache.")
                .register(meterRegistry);
        this.accessOrderKeys = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        if (size() > maxTrackedUsers) {
                            rateLimiterRegistry.remove(eldest.getKey());
                            evictionCounter.increment();
                            return true;
                        }
                        return false;
                    }
                });
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip rate limiting for non-API paths (actuator, swagger, static)
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Per-IP rate-limit on POST /api/auth/login regardless of auth state — the login
        // endpoint is in the permitAll allowlist, so this is where unauthenticated brute-force
        // attempts arrive. Prevents audit-table DoS from LOGIN_FAILURE row floods. Defaults to
        // 10/min/IP (app.rate-limit.login.requests-per-minute); tune per deployment.
        if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(request.getRequestURI())) {
            String ip = request.getRemoteAddr();
            String key = "ip:" + ip + ":login";
            accessOrderKeys.put(key, Boolean.TRUE);
            RateLimiter loginLimiter = rateLimiterRegistry.rateLimiter(key, () ->
                    RateLimiterConfig.custom()
                            .limitForPeriod(loginRequestsPerMinute)
                            .limitRefreshPeriod(Duration.ofMinutes(1))
                            .timeoutDuration(Duration.ZERO)
                            .build()
            );
            if (!loginLimiter.acquirePermission()) {
                log.warn("Login rate-limit exceeded for IP '{}'", ip);
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/problem+json");
                response.setHeader("Retry-After", "60");
                response.getWriter().write("""
                        {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Login rate limit exceeded. Try again in 60 seconds."}""");
                return;
            }
            // Fall through to the user-keyed limiter (a no-op for the anonymous case below).
        }

        // Per-IP rate-limit on POST /api/auth/register regardless of auth state — also in the
        // permitAll allowlist. Tighter cap than login (default 5/min/IP vs 10/min/IP) because
        // each successful registration writes user + audit rows; uncapped, an attacker can
        // create unbounded garbage users + flood the audit table. The collapsed generic-error
        // response from PR #1023 prevents body-based enumeration, but timing/audit-row
        // accumulation still discriminate; this limiter bounds the attack rate. Tune via
        // app.rate-limit.register.requests-per-minute.
        if ("POST".equals(request.getMethod()) && "/api/auth/register".equals(request.getRequestURI())) {
            String ip = request.getRemoteAddr();
            String key = "ip:" + ip + ":register";
            accessOrderKeys.put(key, Boolean.TRUE);
            RateLimiter registerLimiter = rateLimiterRegistry.rateLimiter(key, () ->
                    RateLimiterConfig.custom()
                            .limitForPeriod(registerRequestsPerMinute)
                            .limitRefreshPeriod(Duration.ofMinutes(1))
                            .timeoutDuration(Duration.ZERO)
                            .build()
            );
            if (!registerLimiter.acquirePermission()) {
                log.warn("Register rate-limit exceeded for IP '{}'", ip);
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/problem+json");
                response.setHeader("Retry-After", "60");
                response.getWriter().write("""
                        {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Register rate limit exceeded. Try again in 60 seconds."}""");
                return;
            }
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || SecurityPrincipals.ANONYMOUS_USER.equals(auth.getPrincipal())) {
            filterChain.doFilter(request, response);
            return;
        }

        String identity = auth.getName();
        String key = "user:" + identity;
        accessOrderKeys.put(key, Boolean.TRUE);
        RateLimiter limiter = rateLimiterRegistry.rateLimiter(key, () ->
                RateLimiterConfig.custom()
                        .limitForPeriod(requestsPerSecond)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ofMillis(timeoutMillis))
                        .build()
        );

        if (!limiter.acquirePermission()) {
            log.warn("Rate limit exceeded for user '{}' on {} {}", identity, request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/problem+json");
            response.setHeader("Retry-After", "1");
            response.getWriter().write("""
                    {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Rate limit exceeded. Try again in 1 second."}""");
            return;
        }

        // Stricter dispatch-specific bucket layered on top of the global user limit.
        // /api/runs and /api/runs/stream are the universal-dispatch entry points; each
        // call resolves an agent (potentially invoking the LLM classifier, which is
        // billable) and then runs the resolved agent. Default 60/min/user softens the
        // worst-case cost amplification without affecting normal interactive use.
        if (isDispatchPath(request)) {
            String dispatchKey = "dispatch:" + identity;
            accessOrderKeys.put(dispatchKey, Boolean.TRUE);
            RateLimiter dispatchLimiter = rateLimiterRegistry.rateLimiter(dispatchKey, () ->
                    RateLimiterConfig.custom()
                            .limitForPeriod(dispatchRequestsPerMinute)
                            .limitRefreshPeriod(Duration.ofMinutes(1))
                            .timeoutDuration(Duration.ZERO)
                            .build()
            );
            if (!dispatchLimiter.acquirePermission()) {
                log.warn("Dispatch rate limit exceeded for user '{}' on {} {}",
                        identity, request.getMethod(), request.getRequestURI());
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/problem+json");
                response.setHeader("Retry-After", "60");
                response.getWriter().write("""
                        {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Dispatch rate limit exceeded. Try again in 60 seconds."}""");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isDispatchPath(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return false;
        String uri = request.getRequestURI();
        return "/api/runs".equals(uri) || "/api/runs/stream".equals(uri);
    }
}
