package com.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Coverage manifest for every controller method gated by
 *   {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} — the higher tier above ADMIN
 *   (see {@code RoleHierarchyConfig}: {@code ROLE_SUPER_ADMIN > ROLE_ADMIN}).
 *
 *   <p>Sibling to {@link AdminEndpointCoverageArchTest}; same scanning pattern,
 *   same forward + stale guards, separate manifest because the matrices /
 *   focused tests for the SUPER_ADMIN tier differ from the ADMIN tier.
 *
 *   <p>Each super-admin endpoint must appear on {@link #SUPER_ADMIN_ENDPOINT_COVERAGE}
 *   tagged with the test file that exercises its authz path:
 *   <ul>
 *     <li><b>matrix-super:</b> — covered by
 *         {@code AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS}</li>
 *     <li><b>focused: &lt;FileName&gt;:</b> — dedicated runtime authz test owns the endpoint</li>
 *     <li><b>TODO:</b> — gap; ship a focused test in a follow-on PR</li>
 *   </ul>
 *
 * State: Stateless. Pure-classpath unit test (no Spring context, no Postgres). Runs
 *   in ~0.4s as part of {@code ./mvnw test}.
 */
public class SuperAdminEndpointCoverageArchTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.operativus.agentmanager";

    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, DeleteMapping.class, PatchMapping.class);

    /**
     * Matches {@code @PreAuthorize} expressions that gate on ROLE_SUPER_ADMIN.
     * Broader than the ADMIN regex because the SUPER_ADMIN tier is a smaller surface
     * (currently 7 endpoints across 3 controllers) and we want to be sure not to miss
     * any reflective annotation visibility quirks. The "SUPER_ADMIN" literal is the
     * only super-admin-specific token, so a substring match is sufficient.
     */
    private static final Pattern SUPER_ADMIN_GATE = Pattern.compile("SUPER_ADMIN");

    private static final Set<String> SUPER_ADMIN_ENDPOINT_COVERAGE = Set.of(
            // === IncidentResponseController — method-level gate ===
            "com.operativus.agentmanager.control.controller.IncidentResponseController#haltAllRuns",                          // matrix-super

            // === ComposioAdminController — class-level gate covers all CRUD methods ===
            "com.operativus.agentmanager.control.controller.ComposioAdminController#listActions",                             // matrix-super
            "com.operativus.agentmanager.control.controller.ComposioAdminController#createAction",                            // matrix-super
            "com.operativus.agentmanager.control.controller.ComposioAdminController#updateAction",                            // matrix-super
            "com.operativus.agentmanager.control.controller.ComposioAdminController#deleteAction",                            // matrix-super

            // === ComposioConfigDriftController — class-level gate ===
            "com.operativus.agentmanager.control.controller.ComposioConfigDriftController#getConfigDrift",                    // focused: ComposioConfigDriftAuthzRuntimeTest

            // === ComposioCatalogController — class-level gate (gap #21: catalog discovery + bulk import) ===
            "com.operativus.agentmanager.control.controller.ComposioCatalogController#listCatalog",                           // focused: ComposioCatalogAdminAuthzRuntimeTest
            "com.operativus.agentmanager.control.controller.ComposioCatalogController#importApp",                             // focused: ComposioCatalogAdminAuthzRuntimeTest

            // === FinOpsAdminController — method-level gate on the global valuation-rate write ===
            "com.operativus.agentmanager.control.controller.FinOpsAdminController#updateValuationRate"                        // focused: FinOpsValuationRateAuthzRuntimeTest
    );

    @Test
    void everySuperAdminGatedEndpointMustBeOnTheCoverageManifest() {
        Set<String> live = liveSuperAdminEndpoints();
        Set<String> uncovered = new TreeSet<>(live);
        uncovered.removeAll(SUPER_ADMIN_ENDPOINT_COVERAGE);

        if (!uncovered.isEmpty()) {
            String report = uncovered.stream().sorted()
                    .collect(Collectors.joining("\n  - ", "  - ", ""));
            fail("""
                    Found %d controller method(s) gated by @PreAuthorize hasRole('SUPER_ADMIN')
                    (or hasAuthority ROLE_SUPER_ADMIN) that are NOT on SUPER_ADMIN_ENDPOINT_COVERAGE.
                    Every super-admin endpoint MUST be tagged with the test file that exercises
                    its authz contract.

                    Add each to SUPER_ADMIN_ENDPOINT_COVERAGE with one of:
                      // matrix-super        — covered by AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS
                      // focused: <FileName> — covered by a dedicated runtime authz test
                      // TODO: needs focused authz test — admit the gap; ship the test in a follow-on PR

                    Missing:
                    %s""".formatted(uncovered.size(), report));
        }
    }

    @Test
    void coverageManifestDoesNotContainStaleEntries() {
        Set<String> live = liveSuperAdminEndpoints();
        Set<String> stale = new TreeSet<>(SUPER_ADMIN_ENDPOINT_COVERAGE);
        stale.removeAll(live);
        assertTrue(stale.isEmpty(),
                "SUPER_ADMIN_ENDPOINT_COVERAGE contains entries that no longer match any "
                        + "super-admin-gated controller method (method renamed, gate removed, or "
                        + "controller deleted). Remove:\n  - " + String.join("\n  - ", stale));
    }

    // ─── scanning helpers ────────────────────────────────────────────────────

    private static Set<String> liveSuperAdminEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<String> hits = new TreeSet<>();
        List<String> classpathErrors = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE)) {
            String fqcn = bd.getBeanClassName();
            if (fqcn == null) continue;
            Class<?> controllerClass;
            try {
                controllerClass = Class.forName(fqcn);
            } catch (Throwable e) {
                classpathErrors.add(fqcn + ": " + e.getMessage());
                continue;
            }
            PreAuthorize classGate = controllerClass.getAnnotation(PreAuthorize.class);
            boolean classIsSuperAdminGated = classGate != null
                    && SUPER_ADMIN_GATE.matcher(classGate.value()).find();

            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!hasMappingAnnotation(method)) continue;
                PreAuthorize methodGate = method.getAnnotation(PreAuthorize.class);
                boolean isSuper = methodGate != null
                        ? SUPER_ADMIN_GATE.matcher(methodGate.value()).find()
                        : classIsSuperAdminGated;
                if (isSuper) {
                    hits.add(controllerClass.getName() + "#" + method.getName());
                }
            }
        }
        if (!classpathErrors.isEmpty()) {
            throw new IllegalStateException(
                    "Classpath scan errors:\n  - " + String.join("\n  - ", classpathErrors));
        }
        return hits;
    }

    private static boolean hasMappingAnnotation(Method method) {
        for (Class<? extends Annotation> ann : MAPPING_ANNOTATIONS) {
            if (method.isAnnotationPresent(ann)) return true;
        }
        return false;
    }
}
