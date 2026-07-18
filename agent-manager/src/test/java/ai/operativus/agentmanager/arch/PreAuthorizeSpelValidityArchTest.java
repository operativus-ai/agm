package ai.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Forward guard against silently-broken {@code @PreAuthorize}
 *   expressions. Two failure modes are caught at build time:
 *   <ol>
 *     <li><b>SpEL syntax errors</b> — every expression is parsed via Spring's
 *         {@link SpelExpressionParser}. A malformed expression that today only fails at
 *         runtime (when a request reaches the gate) is surfaced as a compile-time-equivalent
 *         test failure.</li>
 *     <li><b>Typo'd role literals</b> — every {@code hasRole(...)} / {@code hasAuthority(...)} /
 *         {@code hasAnyRole(...)} / {@code hasAnyAuthority(...)} string-literal argument is
 *         validated against the canonical role allowlist derived from
 *         {@link ai.operativus.agentmanager.core.model.enums.RoleType}. A typo like
 *         {@code hasRole('ADIMN')} would otherwise silently allow no one (Spring evaluates
 *         the gate, finds no authority match, denies — but reviewers may misread the typo as
 *         "deliberate stricter gate"). Likewise {@code hasRole('ROLE_ADMIN')} is wrong because
 *         Spring re-prepends {@code ROLE_} for {@code hasRole}; this test catches that too.</li>
 *   </ol>
 *
 *   <p>Sibling to {@link AdminEndpointCoverageArchTest} and
 *   {@link SuperAdminEndpointCoverageArchTest}; same scanning pattern (pure classpath, no
 *   Spring context). Scope is wider than the coverage tests — it scans every concrete class
 *   under the base package, not just controllers, because four services
 *   ({@code ModelService}, {@code PersistentJobQueueService}, {@code IncidentResponseService},
 *   {@code AgentAdminService}) also carry {@code @PreAuthorize} annotations.
 *
 *   <p>The allowlist intentionally omits hierarchy-aware semantics: this test does not assert
 *   that {@code hasRole('USER')} is "weaker than" {@code hasRole('ADMIN')}. It only asserts
 *   the literal names a real role. Hierarchy semantics belong in the runtime authz tests.
 *
 * State: Stateless. Pure-classpath unit test (no Spring context, no Postgres). Runs as part
 *   of {@code ./mvnw test}.
 */
public class PreAuthorizeSpelValidityArchTest {

    private static final String BASE_PACKAGE = "ai.operativus.agentmanager";

    /**
     * Valid arguments for {@code hasRole(...)} / {@code hasAnyRole(...)}. Spring Security
     * strips and re-prepends the {@code ROLE_} prefix for these methods, so the literal must
     * be the bare suffix (e.g. {@code "ADMIN"}, not {@code "ROLE_ADMIN"}).
     */
    private static final Set<String> VALID_HAS_ROLE_LITERALS = Set.of(
            "VIEWER", "USER", "OPERATOR", "ADMIN", "SUPER_ADMIN",
            "A2A_AGENT", "MFA_AUTHENTICATED");

    /**
     * Valid arguments for {@code hasAuthority(...)} / {@code hasAnyAuthority(...)}. These take
     * the fully-qualified authority string, including the {@code ROLE_} prefix.
     */
    private static final Set<String> VALID_HAS_AUTHORITY_LITERALS = Set.of(
            "ROLE_VIEWER", "ROLE_USER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_SUPER_ADMIN",
            "ROLE_A2A_AGENT", "ROLE_MFA_AUTHENTICATED");

    private static final Pattern HAS_ROLE_LITERAL =
            Pattern.compile("hasRole\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern HAS_AUTHORITY_LITERAL =
            Pattern.compile("hasAuthority\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern HAS_ANY_ROLE_BLOCK =
            Pattern.compile("hasAnyRole\\(([^)]*)\\)");
    private static final Pattern HAS_ANY_AUTHORITY_BLOCK =
            Pattern.compile("hasAnyAuthority\\(([^)]*)\\)");
    private static final Pattern QUOTED_LITERAL =
            Pattern.compile("['\"]([^'\"]+)['\"]");

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    @Test
    void everyPreAuthorizeExpressionParsesAsSpel() {
        List<String> violations = new ArrayList<>();
        for (Annotated a : findAllPreAuthorizeAnnotations()) {
            try {
                PARSER.parseExpression(a.expression());
            } catch (Throwable t) {
                violations.add(a.location() + ": SpEL parse error — " + t.getMessage()
                        + " (expression: \"" + a.expression() + "\")");
            }
        }
        if (!violations.isEmpty()) {
            fail(format("@PreAuthorize expressions that fail SpEL parsing", violations));
        }
    }

    @Test
    void everyPreAuthorizeExpressionReferencesKnownRoleLiterals() {
        List<String> violations = new ArrayList<>();
        for (Annotated a : findAllPreAuthorizeAnnotations()) {
            String expr = a.expression();
            validateSingle(a.location(), expr, HAS_ROLE_LITERAL,
                    VALID_HAS_ROLE_LITERALS, "hasRole", violations);
            validateSingle(a.location(), expr, HAS_AUTHORITY_LITERAL,
                    VALID_HAS_AUTHORITY_LITERALS, "hasAuthority", violations);
            validateMulti(a.location(), expr, HAS_ANY_ROLE_BLOCK,
                    VALID_HAS_ROLE_LITERALS, "hasAnyRole", violations);
            validateMulti(a.location(), expr, HAS_ANY_AUTHORITY_BLOCK,
                    VALID_HAS_AUTHORITY_LITERALS, "hasAnyAuthority", violations);
        }
        if (!violations.isEmpty()) {
            fail(format("@PreAuthorize expressions referencing unknown role literals",
                    violations));
        }
    }

    // ─── validation helpers ──────────────────────────────────────────────────

    private static void validateSingle(String location, String expr, Pattern callPattern,
                                       Set<String> allowed, String fn, List<String> out) {
        Matcher m = callPattern.matcher(expr);
        while (m.find()) {
            String literal = m.group(1);
            if (!allowed.contains(literal)) {
                out.add(location + ": " + fn + "('" + literal + "') — not a known role literal "
                        + "(expected one of " + allowed + ") (expression: \"" + expr + "\")");
            }
        }
    }

    private static void validateMulti(String location, String expr, Pattern blockPattern,
                                      Set<String> allowed, String fn, List<String> out) {
        Matcher block = blockPattern.matcher(expr);
        while (block.find()) {
            Matcher inner = QUOTED_LITERAL.matcher(block.group(1));
            while (inner.find()) {
                String literal = inner.group(1);
                if (!allowed.contains(literal)) {
                    out.add(location + ": " + fn + "('" + literal + "') — not a known role "
                            + "literal (expected one of " + allowed + ") (expression: \""
                            + expr + "\")");
                }
            }
        }
    }

    // ─── scanning helpers ────────────────────────────────────────────────────

    private record Annotated(String location, String expression) {
    }

    private static List<Annotated> findAllPreAuthorizeAnnotations() {
        List<Annotated> hits = new ArrayList<>();
        for (Class<?> cls : findAllClassesInBasePackage()) {
            PreAuthorize classGate = cls.getAnnotation(PreAuthorize.class);
            if (classGate != null) {
                hits.add(new Annotated(cls.getName() + " <class-level>", classGate.value()));
            }
            for (Method method : cls.getDeclaredMethods()) {
                PreAuthorize methodGate = method.getAnnotation(PreAuthorize.class);
                if (methodGate != null) {
                    hits.add(new Annotated(cls.getName() + "#" + method.getName(),
                            methodGate.value()));
                }
            }
        }
        return hits;
    }

    private static List<Class<?>> findAllClassesInBasePackage() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);

        List<Class<?>> classes = new ArrayList<>();
        List<String> classpathErrors = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String fqcn = bd.getBeanClassName();
            if (fqcn == null) continue;
            try {
                classes.add(Class.forName(fqcn, false, Thread.currentThread().getContextClassLoader()));
            } catch (Throwable e) {
                classpathErrors.add(fqcn + ": " + e.getMessage());
            }
        }
        if (!classpathErrors.isEmpty()) {
            throw new IllegalStateException(
                    "Classpath scan errors:\n  - " + String.join("\n  - ", classpathErrors));
        }
        return classes;
    }

    private static String format(String header, List<String> violations) {
        return header + " (" + violations.size() + "):\n  - "
                + String.join("\n  - ", new TreeSet<>(violations));
    }
}
