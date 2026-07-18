package com.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Pins the public-endpoint surface exposed by Spring Security's
 *   {@code .permitAll()} configuration. Drift in either direction — a new public endpoint
 *   silently added, or a hardening removed from {@code application-prod.properties} —
 *   fails the build.
 *
 *   <p>Three things are pinned:
 *   <ol>
 *     <li><b>{@code SecurityConfig}'s {@code @Value} default</b> for
 *         {@code app.security.public-paths}: the fallback used when no profile-level
 *         override is set. Today: 6 patterns (auth, health, error, actuator, swagger, api-docs).</li>
 *     <li><b>{@code application-prod.properties} override</b>: prod-tier hardening that
 *         narrows the public surface. Today: 3 patterns (auth, health, error). Removing
 *         this override silently widens production to actuator/swagger/api-docs.</li>
 *     <li><b>Non-prod profiles do NOT override</b>: {@code application.properties} and
 *         {@code application-dev.properties} must not set {@code app.security.public-paths}
 *         — otherwise the default isn't actually exercised.</li>
 *   </ol>
 *
 *   <p>Each pinned path carries a comment in this test explaining WHY it's public. New
 *   public paths must add (a) their pattern to the appropriate {@code Set}, (b) the
 *   rationale comment, AND (c) a justification in the PR description.
 *
 *   <p>Note: this test pins source-code state, not runtime state. A runtime override via
 *   environment variable or external config would bypass this test. Defense-in-depth is the
 *   responsibility of deployment configuration audits.
 *
 * State: Stateless. Pure-classpath unit test. Runs in {@code <1s} as part of
 *   {@code ./mvnw test}.
 */
public class PermitAllPathEnumerationArchTest {

    private static final Path SECURITY_CONFIG_SOURCE = Path.of(
            "src/main/java/com/operativus/agentmanager/control/config/SecurityConfig.java");
    private static final Path APP_PROPERTIES = Path.of(
            "src/main/resources/application.properties");
    private static final Path APP_DEV_PROPERTIES = Path.of(
            "src/main/resources/application-dev.properties");
    private static final Path APP_PROD_PROPERTIES = Path.of(
            "src/main/resources/application-prod.properties");

    /**
     * Pattern that captures the inside of the {@code @Value("${app.security.public-paths:...}")}
     * default. Group 1 is the comma-separated path list.
     */
    private static final Pattern AT_VALUE_DEFAULT = Pattern.compile(
            "@Value\\(\\s*\"\\$\\{app\\.security\\.public-paths:([^}]+)\\}\"\\s*\\)");

    private static final Pattern PROPERTY_LINE = Pattern.compile(
            "^\\s*app\\.security\\.public-paths\\s*=\\s*(.+)$", Pattern.MULTILINE);

    /**
     * Default public surface — the {@code @Value} fallback in {@code SecurityConfig.java}.
     * Used when no profile-level override sets {@code app.security.public-paths}.
     */
    private static final Set<String> PINNED_DEFAULT_PUBLIC_PATHS = Set.of(
            "/api/auth/**",       // login / register / refresh — bootstrap auth surface
            "/api/v1/health/**",  // K8s liveness + readiness probes
            "/error",             // Spring's error endpoint — required for 4xx/5xx propagation
            "/actuator/**",       // management endpoints — gated separately by management.endpoints config
            "/swagger-ui/**",     // OpenAPI UI — dev convenience; prod profile narrows this away
            "/v3/api-docs/**",    // OpenAPI JSON spec — dev convenience; prod profile narrows this away
            "/workflows/ws");     // workflow live WS — JWT-gated at handshake (JwtHandshakeInterceptor, ?token=)

    /**
     * Production-tier public surface — the {@code application-prod.properties} override.
     * Intentionally narrower than the default: actuator/swagger/api-docs are NOT public in
     * production because they expose introspection that's only safe for dev tooling.
     */
    private static final Set<String> PINNED_PROD_PUBLIC_PATHS = Set.of(
            "/api/auth/**",       // login / register / refresh
            "/api/v1/health/**",  // K8s liveness + readiness probes
            "/error",             // Spring's error endpoint
            "/workflows/ws");     // workflow live WS — JWT-gated at handshake (JwtHandshakeInterceptor, ?token=)

    @Test
    void securityConfigDefaultPublicPathsMatchPinnedAllowlist() {
        String source = readSource(SECURITY_CONFIG_SOURCE);
        Matcher m = AT_VALUE_DEFAULT.matcher(source);
        if (!m.find()) {
            fail("Could not locate @Value(\"${app.security.public-paths:...}\") in "
                    + SECURITY_CONFIG_SOURCE
                    + " — the property name or @Value pattern may have changed. Update the "
                    + "AT_VALUE_DEFAULT regex in this test.");
        }
        Set<String> actual = splitCommaList(m.group(1));
        assertPinnedEquals(PINNED_DEFAULT_PUBLIC_PATHS, actual,
                "SecurityConfig @Value default for app.security.public-paths");
    }

    @Test
    void applicationProdPropertiesPublicPathsMatchPinnedAllowlist() {
        Set<String> actual = readPropertyAsSet(APP_PROD_PROPERTIES);
        if (actual.isEmpty()) {
            fail("application-prod.properties no longer sets app.security.public-paths — "
                    + "this removes the prod hardening that narrows the public surface from 6 "
                    + "patterns to 3. Either restore the override (preferred — keeps actuator/"
                    + "swagger/api-docs private in production), or update this test if the "
                    + "hardening is intentionally being removed.");
        }
        assertPinnedEquals(PINNED_PROD_PUBLIC_PATHS, actual,
                "application-prod.properties override of app.security.public-paths");
    }

    @Test
    void nonProdProfilesMustNotOverrideAppSecurityPublicPaths() {
        for (Path profile : new Path[]{APP_PROPERTIES, APP_DEV_PROPERTIES}) {
            Set<String> override = readPropertyAsSet(profile);
            if (!override.isEmpty()) {
                fail("Unexpected override of app.security.public-paths in " + profile
                        + ": " + override + ". The default (in SecurityConfig.java's @Value) "
                        + "is the authoritative non-prod value. If this override is intentional, "
                        + "add a new pinned set + test method to this arch test.");
            }
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static Set<String> readPropertyAsSet(Path propertiesFile) {
        String content = readSource(propertiesFile);
        Matcher m = PROPERTY_LINE.matcher(content);
        if (!m.find()) return Set.of();
        return splitCommaList(m.group(1));
    }

    private static Set<String> splitCommaList(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String readSource(Path p) {
        try {
            return Files.readString(p);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to read " + p, e);
        }
    }

    private static void assertPinnedEquals(Set<String> expected, Set<String> actual,
                                           String location) {
        if (expected.equals(actual)) return;

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(expected);
        Set<String> removed = new TreeSet<>(expected);
        removed.removeAll(actual);
        fail("Public-path drift on " + location
                + "\n  added (not pinned):     " + added
                + "\n  removed (still pinned): " + removed
                + "\n\nA public endpoint change widens or narrows the unauthenticated attack "
                + "surface. Update the pinned Set in this test IN THE SAME PR — and justify "
                + "the change in the PR description (what's the path, why is it public, what "
                + "data is exposed).");
        // Reachable only if the above fail() returns — guards against future Assertions API shift.
        assertEquals(expected.size(), actual.size(),
                "Public path count drifted on " + location);
    }
}
