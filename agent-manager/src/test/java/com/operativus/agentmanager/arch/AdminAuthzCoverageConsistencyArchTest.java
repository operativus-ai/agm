package com.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Cross-test consistency guard between the static
 *   {@link AdminEndpointCoverageArchTest} / {@link SuperAdminEndpointCoverageArchTest}
 *   manifests and the runtime {@code AdminEndpointAuthzRuntimeTest} matrix lists.
 *
 *   <p>Drift this catches (in both directions):
 *   <ul>
 *     <li><b>Coverage → runtime</b>: a coverage entry tagged {@code // matrix} (or
 *         {@code // matrix-super}) whose controller method's {@code (path, httpMethod)} is
 *         NOT exercised by the runtime matrix list it claims coverage from. Symptom of
 *         someone trimming {@code ADMIN_ENDPOINTS}/{@code SUPER_ADMIN_ENDPOINTS} without
 *         retagging the coverage manifest — the tag now lies.</li>
 *     <li><b>Runtime → coverage</b>: a runtime {@code ADMIN_ENDPOINTS}/
 *         {@code SUPER_ADMIN_ENDPOINTS} entry whose handling controller method IS
 *         controller-level admin-gated (class-level or method-level
 *         {@code @PreAuthorize("hasRole('ADMIN'/'SUPER_ADMIN')")}), but whose coverage
 *         manifest tag is NOT {@code matrix}/{@code matrix-super}. Symptom of someone
 *         adding to the runtime matrix without updating the coverage tag — the manifest
 *         under-reports coverage. Endpoints whose handlers have NO controller-level
 *         {@code @PreAuthorize} (e.g. {@code ModelController.createModel}, whose authz
 *         lives at the service layer on {@code ModelService.createModel}) are skipped —
 *         the coverage manifest only catalogs controller-level gates.</li>
 *   </ul>
 *
 *   <p>Why source-text parsing rather than reflection on the runtime test:
 *   {@code AdminEndpointAuthzRuntimeTest extends BaseIntegrationTest}, and
 *   {@code BaseIntegrationTest} has a {@code static {{ POSTGRES.start(); }}} block. Loading
 *   the runtime test class via {@link Class#forName} would spin up the pgvector
 *   Testcontainer (~20s) — unacceptable for a pure-classpath arch test. So this test
 *   parses both the coverage manifest source and the runtime test source as text.
 *
 *   <p>Path-template matching: coverage entries are {@code Controller#method} identifiers,
 *   which we resolve via reflection to {@code @RequestMapping}/{@code @GetMapping}/etc
 *   templates (with placeholders like {@code /{id}}). The runtime test uses concrete URLs
 *   (with synthetic ids like {@code /non-existent-job-id}). {@link AntPathMatcher} bridges
 *   the two — it natively understands {@code {var}} placeholders as single-segment
 *   wildcards.
 *
 * State: Stateless. Pure-classpath unit test (no Spring context, no Postgres,
 *   no Testcontainers). Runs in ~0.3s as part of {@code ./mvnw test}.
 */
public class AdminAuthzCoverageConsistencyArchTest {

    private static final Path COVERAGE_SOURCE = Path.of(
            "src/test/java/com/operativus/agentmanager/arch/AdminEndpointCoverageArchTest.java");
    private static final Path SUPER_COVERAGE_SOURCE = Path.of(
            "src/test/java/com/operativus/agentmanager/arch/SuperAdminEndpointCoverageArchTest.java");
    private static final Path RUNTIME_TEST_SOURCE = Path.of(
            "src/test/java/com/operativus/agentmanager/integration/security/AdminEndpointAuthzRuntimeTest.java");

    /**
     * Matches a coverage-manifest line like
     * {@code "com.foo.Bar#baz",  // matrix}
     * — captures FQCN, method name, and the first tag word (matrix / matrix-super / focused / TODO).
     */
    private static final Pattern COVERAGE_ENTRY_LINE = Pattern.compile(
            "\"([\\w.$]+)#(\\w+)\"\\s*,?\\s*//\\s*([A-Za-z][\\w-]*)");

    private static final Pattern RUNTIME_ENDPOINT_LINE = Pattern.compile(
            "new\\s+EndpointSpec\\(\\s*\"([^\"]+)\"\\s*,\\s*HttpMethod\\.(\\w+)");

    /** Matches {@code @PreAuthorize} expressions that gate on ROLE_ADMIN. */
    private static final Pattern ADMIN_GATE = Pattern.compile(
            "hasRole\\(\\s*['\"]ADMIN['\"]\\s*\\)"
                    + "|hasAuthority\\(\\s*['\"]ROLE_ADMIN['\"]\\s*\\)");

    /** Matches {@code @PreAuthorize} expressions that gate on ROLE_SUPER_ADMIN. */
    private static final Pattern SUPER_ADMIN_GATE = Pattern.compile("SUPER_ADMIN");

    private static final String CONTROLLER_BASE_PACKAGE = "com.operativus.agentmanager";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Test
    void everyMatrixTaggedAdminCoverageEntryIsExercisedByAdminEndpointsList() {
        Map<String, String> tagsByEntry = parseCoverageTags(COVERAGE_SOURCE);
        Set<EndpointKey> runtimeAdmin = parseRuntimeEndpoints("ADMIN_ENDPOINTS");
        assertNoDrift(tagsByEntry, "matrix", runtimeAdmin,
                "AdminEndpointAuthzRuntimeTest.ADMIN_ENDPOINTS",
                "AdminEndpointCoverageArchTest");
    }

    @Test
    void everyMatrixSuperTaggedAdminCoverageEntryIsExercisedBySuperAdminEndpointsList() {
        Map<String, String> tagsByEntry = parseCoverageTags(COVERAGE_SOURCE);
        Set<EndpointKey> runtimeSuper = parseRuntimeEndpoints("SUPER_ADMIN_ENDPOINTS");
        assertNoDrift(tagsByEntry, "matrix-super", runtimeSuper,
                "AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS",
                "AdminEndpointCoverageArchTest");
    }

    @Test
    void everyMatrixSuperTaggedSuperAdminCoverageEntryIsExercisedBySuperAdminEndpointsList() {
        Map<String, String> tagsByEntry = parseCoverageTags(SUPER_COVERAGE_SOURCE);
        Set<EndpointKey> runtimeSuper = parseRuntimeEndpoints("SUPER_ADMIN_ENDPOINTS");
        assertNoDrift(tagsByEntry, "matrix-super", runtimeSuper,
                "AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS",
                "SuperAdminEndpointCoverageArchTest");
    }

    @Test
    void everyAdminEndpointWithControllerLevelGateIsTaggedMatrixInCoverageManifest() {
        Set<EndpointKey> runtimeAdmin = parseRuntimeEndpoints("ADMIN_ENDPOINTS");
        Map<String, String> tagsByEntry = parseCoverageTags(COVERAGE_SOURCE);
        assertReverseDirection(runtimeAdmin, tagsByEntry, ADMIN_GATE, "matrix",
                "AdminEndpointAuthzRuntimeTest.ADMIN_ENDPOINTS",
                "AdminEndpointCoverageArchTest");
    }

    @Test
    void everySuperAdminEndpointWithControllerLevelGateIsTaggedMatrixSuperInCoverageManifest() {
        Set<EndpointKey> runtimeSuper = parseRuntimeEndpoints("SUPER_ADMIN_ENDPOINTS");
        Map<String, String> tagsByEntry = parseCoverageTags(SUPER_COVERAGE_SOURCE);
        assertReverseDirection(runtimeSuper, tagsByEntry, SUPER_ADMIN_GATE, "matrix-super",
                "AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS",
                "SuperAdminEndpointCoverageArchTest");
    }

    // ─── consistency check ───────────────────────────────────────────────────

    private static void assertNoDrift(Map<String, String> tagsByEntry, String tag,
                                      Set<EndpointKey> runtimeEndpoints,
                                      String runtimeListName, String coverageSourceName) {
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, String> entry : tagsByEntry.entrySet()) {
            if (!tag.equals(entry.getValue())) continue;
            String fqcnMethod = entry.getKey();
            List<EndpointKey> templates = resolveControllerEndpoints(fqcnMethod);
            if (templates.isEmpty()) {
                drift.add(fqcnMethod + " — tagged `" + tag + "` in " + coverageSourceName
                        + " but no @*Mapping found on the controller method (renamed? deleted?)");
                continue;
            }
            boolean matched = templates.stream().anyMatch(t -> runtimeEndpoints.stream()
                    .anyMatch(r -> r.method().equals(t.method())
                            && MATCHER.match(t.path(), r.path())));
            if (!matched) {
                drift.add(fqcnMethod + " — tagged `" + tag + "` in " + coverageSourceName
                        + " but " + runtimeListName + " has no entry matching template(s) "
                        + templates);
            }
        }
        if (!drift.isEmpty()) {
            fail(("Cross-test drift between coverage manifest and runtime matrix (%d):%n"
                    + "  - %s%n%nFix either by adding the endpoint to " + runtimeListName
                    + ", or by retagging the coverage entry from `" + tag
                    + "` to `focused: <FileName>` or `TODO: needs focused authz test`.")
                    .formatted(drift.size(), String.join(
                            String.format("%n  - "), new TreeSet<>(drift))));
        }
    }

    private static void assertReverseDirection(Set<EndpointKey> runtimeEndpoints,
                                               Map<String, String> tagsByEntry,
                                               Pattern gate, String requiredTag,
                                               String runtimeListName,
                                               String coverageSourceName) {
        Map<EndpointKey, String> handlersByTemplate = scanAllControllerHandlers();
        List<String> drift = new ArrayList<>();
        for (EndpointKey url : runtimeEndpoints) {
            Optional<String> handler = findHandler(url, handlersByTemplate);
            if (handler.isEmpty()) continue;
            String fqcnMethod = handler.get();
            if (!isControllerGated(fqcnMethod, gate)) continue;
            String tag = tagsByEntry.get(fqcnMethod);
            if (!requiredTag.equals(tag)) {
                drift.add(url.method() + " " + url.path() + " → " + fqcnMethod
                        + " — controller is gated by the expected role and exercised by "
                        + runtimeListName + ", but coverage tag is `"
                        + (tag == null ? "<missing entry>" : tag)
                        + "` (expected `" + requiredTag + "`)");
            }
        }
        if (!drift.isEmpty()) {
            fail(("Cross-test drift — " + runtimeListName + " exercises endpoints whose "
                    + "controller-level " + requiredTag + " gate is not reflected in "
                    + coverageSourceName + " (%d):%n  - %s%n%nFix either by retagging the "
                    + "coverage entry as `" + requiredTag + "`, or by removing the runtime "
                    + "entry if it's no longer needed.")
                    .formatted(drift.size(), String.join(
                            String.format("%n  - "), new TreeSet<>(drift))));
        }
    }

    private static Optional<String> findHandler(EndpointKey url,
                                                Map<EndpointKey, String> handlersByTemplate) {
        return handlersByTemplate.entrySet().stream()
                .filter(e -> e.getKey().method().equals(url.method())
                        && MATCHER.match(e.getKey().path(), url.path()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static boolean isControllerGated(String fqcnMethod, Pattern gate) {
        String[] parts = fqcnMethod.split("#", 2);
        Class<?> cls;
        try {
            cls = Class.forName(parts[0], false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable e) {
            return false;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(parts[1])) continue;
            PreAuthorize methodGate = m.getAnnotation(PreAuthorize.class);
            if (methodGate != null) {
                return gate.matcher(methodGate.value()).find();
            }
        }
        PreAuthorize classGate = cls.getAnnotation(PreAuthorize.class);
        return classGate != null && gate.matcher(classGate.value()).find();
    }

    private static Map<EndpointKey, String> scanAllControllerHandlers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Map<EndpointKey, String> handlers = new LinkedHashMap<>();
        for (var bd : scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE)) {
            String fqcn = bd.getBeanClassName();
            if (fqcn == null) continue;
            Class<?> cls;
            try {
                cls = Class.forName(fqcn, false,
                        Thread.currentThread().getContextClassLoader());
            } catch (Throwable e) {
                continue;
            }
            String[] basePaths = pathsOrEmpty(
                    cls.isAnnotationPresent(RequestMapping.class)
                            ? cls.getAnnotation(RequestMapping.class).value()
                            : new String[0]);
            for (Method m : cls.getDeclaredMethods()) {
                for (EndpointKey ep : resolveMethodEndpoints(m, basePaths)) {
                    handlers.putIfAbsent(ep, cls.getName() + "#" + m.getName());
                }
            }
        }
        return handlers;
    }

    // ─── source parsing ──────────────────────────────────────────────────────

    private record EndpointKey(String path, HttpMethod method) {
    }

    private static Map<String, String> parseCoverageTags(Path source) {
        try {
            Map<String, String> tags = new LinkedHashMap<>();
            for (String line : Files.readAllLines(source)) {
                Matcher m = COVERAGE_ENTRY_LINE.matcher(line);
                if (m.find()) {
                    tags.put(m.group(1) + "#" + m.group(2), m.group(3));
                }
            }
            if (tags.isEmpty()) {
                throw new IllegalStateException(
                        "Parsed zero coverage entries from " + source
                                + " — file format may have drifted; update the COVERAGE_ENTRY_LINE regex.");
            }
            return tags;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to read " + source, e);
        }
    }

    private static Set<EndpointKey> parseRuntimeEndpoints(String fieldName) {
        try {
            String content = Files.readString(RUNTIME_TEST_SOURCE);
            String marker = "List<EndpointSpec> " + fieldName + " = List.of(";
            int start = content.indexOf(marker);
            if (start < 0) {
                throw new IllegalStateException(
                        "Could not locate field `" + fieldName + "` in " + RUNTIME_TEST_SOURCE
                                + " — runtime test source may have been restructured.");
            }
            int depth = 1;
            int idx = start + marker.length();
            while (idx < content.length() && depth > 0) {
                char c = content.charAt(idx);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                idx++;
            }
            String section = content.substring(start, idx);

            Set<EndpointKey> endpoints = new java.util.LinkedHashSet<>();
            Matcher m = RUNTIME_ENDPOINT_LINE.matcher(section);
            while (m.find()) {
                endpoints.add(new EndpointKey(m.group(1), HttpMethod.valueOf(m.group(2))));
            }
            if (endpoints.isEmpty()) {
                throw new IllegalStateException(
                        "Parsed zero EndpointSpec entries from " + fieldName + " — runtime test "
                                + "source format may have drifted; update RUNTIME_ENDPOINT_LINE regex.");
            }
            return endpoints;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to read " + RUNTIME_TEST_SOURCE, e);
        }
    }

    // ─── controller reflection ──────────────────────────────────────────────

    private static List<EndpointKey> resolveControllerEndpoints(String fqcnMethod) {
        String[] parts = fqcnMethod.split("#", 2);
        Class<?> cls;
        try {
            cls = Class.forName(parts[0], false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable e) {
            return List.of();
        }
        String[] basePaths = pathsOrEmpty(
                cls.isAnnotationPresent(RequestMapping.class)
                        ? cls.getAnnotation(RequestMapping.class).value()
                        : new String[0]);

        List<EndpointKey> endpoints = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(parts[1])) continue;
            endpoints.addAll(resolveMethodEndpoints(m, basePaths));
        }
        return endpoints;
    }

    private static List<EndpointKey> resolveMethodEndpoints(Method m, String[] basePaths) {
        List<EndpointKey> endpoints = new ArrayList<>();
        addMappings(endpoints, basePaths, m.getAnnotation(GetMapping.class), HttpMethod.GET);
        addMappings(endpoints, basePaths, m.getAnnotation(PostMapping.class), HttpMethod.POST);
        addMappings(endpoints, basePaths, m.getAnnotation(PutMapping.class), HttpMethod.PUT);
        addMappings(endpoints, basePaths, m.getAnnotation(DeleteMapping.class), HttpMethod.DELETE);
        addMappings(endpoints, basePaths, m.getAnnotation(PatchMapping.class), HttpMethod.PATCH);
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        if (rm != null) {
            String[] methodPaths = pathsOrEmpty(rm.value());
            if (rm.method().length == 0) {
                addPaths(endpoints, basePaths, methodPaths, HttpMethod.GET);
            } else {
                for (var requestMethod : rm.method()) {
                    addPaths(endpoints, basePaths, methodPaths,
                            HttpMethod.valueOf(requestMethod.name()));
                }
            }
        }
        return endpoints;
    }

    private static void addMappings(List<EndpointKey> out, String[] basePaths,
                                    java.lang.annotation.Annotation ann, HttpMethod method) {
        if (ann == null) return;
        String[] paths;
        try {
            paths = (String[]) ann.annotationType().getMethod("value").invoke(ann);
        } catch (Throwable e) {
            paths = new String[0];
        }
        addPaths(out, basePaths, pathsOrEmpty(paths), method);
    }

    private static void addPaths(List<EndpointKey> out, String[] basePaths, String[] paths,
                                 HttpMethod method) {
        for (String base : basePaths) {
            for (String suffix : paths) {
                out.add(new EndpointKey(normalize(base + suffix), method));
            }
        }
    }

    private static String[] pathsOrEmpty(String[] paths) {
        return paths.length == 0 ? new String[]{""} : paths;
    }

    private static String normalize(String p) {
        String out = p.replaceAll("/+", "/");
        if (out.isEmpty()) return "/";
        if (out.length() > 1 && out.endsWith("/")) return out.substring(0, out.length() - 1);
        return out;
    }
}
