package ai.operativus.agentmanager.control.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;

/**
 * Domain Responsibility: Intercepts A2A networking boundary requests carrying
 * {@code X-A2A-Api-Key} headers and validates them for machine-to-machine authentication.
 *
 * Gap 2.3 Implementation: Existing {@code /api/v1/**} routes rely on OAuth session logic
 * (JWT Bearer tokens) unsuited for headless server-to-server calls. This filter runs
 * alongside {@code JwtAuthenticationFilter} and handles the distinct M2M credential type.
 *
 * Validation strategy:
 * 1. Only activates on requests matching {@code /api/v1/a2a/**} (other paths are skipped).
 * 2. Reads the {@code X-A2A-Api-Key} header.
 * 3. Validates the key against the in-memory approved key set (bootstrapped from DB at
 *    startup; a JPA-backed {@code ApiKeyRepository} replaces this in production).
 * 4. On success: populates {@code SecurityContextHolder} with {@link ApiKeyAuthentication}.
 * 5. On failure: returns {@code 401 Unauthorized} immediately, blocking further processing.
 *
 * Note: If the SecurityContext already contains a valid JWT authentication (admin caller),
 * the filter skips API key validation entirely — allowing dual-auth support without conflict.
 *
 * Architecture:
 * - Constructor injection of the approved key store.
 * - No ApplicationEventPublisher.
 * - Stateless: key set is immutable after construction (hot-reload via bean replacement).
 *
 * State: Stateless (filter logic); the key store is externally managed.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    public static final String A2A_API_KEY_HEADER = "X-A2A-Api-Key";
    private static final String A2A_PATH_PREFIX   = "/api/v1/a2a/";

    /** In-memory approved key store. Production: replace with JPA-backed ApiKeyRepository. */
    private final Set<String> approvedApiKeys;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(
            @Value("${agm.a2a.api-keys:}") String rawKeys,
            ObjectMapper objectMapper) {
        if (rawKeys == null || rawKeys.isBlank()) {
            this.approvedApiKeys = Collections.emptySet();
        } else {
            this.approvedApiKeys = new HashSet<>(Arrays.asList(rawKeys.split(",")));
        }
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only intercept A2A boundary paths
        return !request.getRequestURI().startsWith(A2A_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Skip if a valid JWT authentication is already present (admin/internal caller)
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawKey = request.getHeader(A2A_API_KEY_HEADER);

        if (!StringUtils.hasText(rawKey)) {
            log.debug("ApiKeyAuthFilter: no {} header on A2A path {} — rejecting",
                A2A_API_KEY_HEADER, request.getRequestURI());
            reject(response, "Missing X-A2A-Api-Key header.");
            return;
        }

        if (!isValidKeyConstantTime(rawKey)) {
            log.warn("ApiKeyAuthFilter: invalid API key presented on path {} — rejecting",
                request.getRequestURI());
            reject(response, "Invalid or revoked API key.");
            return;
        }

        // Key is valid — derive a stable key identifier (first 8 chars) for the principal name
        String keyId = "apikey:" + rawKey.substring(0, Math.min(8, rawKey.length())) + "…";
        ApiKeyAuthentication auth = new ApiKeyAuthentication(keyId, true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        log.debug("ApiKeyAuthFilter: authenticated A2A caller keyId={} path={}", keyId, request.getRequestURI());
        filterChain.doFilter(request, response);
    }

    /**
     * @summary Validates an API key using constant-time comparison to prevent timing attacks.
     * @logic
     * Hashes both the candidate key and each approved key with SHA-256 before comparing
     * via {@link MessageDigest#isEqual}. This ensures:
     *  1. Both compared byte arrays are always the same length (32 bytes), eliminating
     *     length-based side-channels that a naive {@code String.equals()} comparison exposes.
     *  2. The loop never short-circuits on a match — all approved keys are always checked,
     *     so an attacker cannot determine a key's position in the set from response timing.
     *
     * Trade-off: O(n) over the approved key set per request. Acceptable because the set
     * is small (tens of keys at most) and SHA-256 is fast (nanoseconds per hash).
     */
    private boolean isValidKeyConstantTime(String rawKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] candidateHash = md.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            boolean found = false;
            for (String approved : approvedApiKeys) {
                md.reset();
                byte[] approvedHash = md.digest(approved.getBytes(StandardCharsets.UTF_8));
                // isEqual is constant-time for equal-length arrays — both are always 32 bytes
                if (MessageDigest.isEqual(candidateHash, approvedHash)) {
                    found = true;
                    // No break: must always complete the full loop to prevent position timing leaks
                }
            }
            return found;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in all JVMs (JCA spec) — this branch is unreachable
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", message)));
    }
}
