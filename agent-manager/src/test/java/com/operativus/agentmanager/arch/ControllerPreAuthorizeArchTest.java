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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Forward guard against silently changing the {@code @PreAuthorize}
 *   posture of any controller in {@code com.operativus.agentmanager}. Controllers are
 *   bucketed into five sets based on what reflection sees on the compiled class (and, for
 *   the service-layer bucket, on the named service impl class):
 *
 *   <ol>
 *     <li><b>{@link #FULLY_CLASS_GATED}</b> — class-level {@code @PreAuthorize} applies to
 *         every handler. Silent removal of the class-level annotation fails the build.</li>
 *     <li><b>{@link #ALL_METHODS_GATED_NO_CLASS}</b> — no class-level annotation, but every
 *         {@code @*Mapping} method has its own {@code @PreAuthorize}. Pinned per-class with
 *         the expected endpoint count. Removal of a method-level gate fails the build.</li>
 *     <li><b>{@link #MIXED_METHOD_AUTHZ_COUNT}</b> — class has no class-level
 *         {@code @PreAuthorize}; some methods have method-level gates, others don't.
 *         Pinned per-class with the current count of method-level gates.</li>
 *     <li><b>{@link #SERVICE_LAYER_GATED}</b> — controller has no annotations of its own,
 *         but its handlers delegate to a service whose methods carry
 *         {@code @PreAuthorize("hasRole('ADMIN')")}. Spring Security throws
 *         {@code AccessDeniedException} before the service body runs — functionally
 *         equivalent to a class-level controller annotation. Pinned per-class with the
 *         service impl's simple name; the test verifies the impl has at least as many
 *         admin-gated methods as the controller has endpoints.</li>
 *     <li><b>{@link #INTENTIONALLY_UNANNOTATED}</b> — neither class-level nor any
 *         method-level {@code @PreAuthorize}. Endpoint relies on Spring Security's
 *         chain-level {@code .authenticated()} default plus service-layer tenant scoping.
 *         Each entry should fit a rationale.</li>
 *   </ol>
 *
 *   <p><b>What this test does NOT do:</b> assert that every endpoint MUST have
 *   {@code @PreAuthorize}. Many user-facing tenant-scoped endpoints (e.g. listing your own
 *   agents, retrieving your own session) authentically operate on "any authenticated user
 *   in this org" — the authorization happens deeper in the service layer via
 *   {@code AgentContextHolder.getOrgId()} filtering. Forcing {@code @PreAuthorize} on
 *   those would be ceremony without value.
 *
 *   <p><b>Suspected gaps flagged in {@link #INTENTIONALLY_UNANNOTATED}:</b> the original
 *   four flagged by PR #967 have all been audited:
 *   <ul>
 *     <li>{@code PiiAdminController} — fixed by PR #968 (no tenant filter; added class gate)</li>
 *     <li>{@code AgentAdminController} — fixed by PR #969 (admin-intended; added class gate)</li>
 *     <li>{@code TeamsController} — closed by PR #970 (tenant-scoped at service layer)</li>
 *     <li>{@code ModelController} — closed by PR #971 (admin-gated at service layer)</li>
 *   </ul>
 *   Any NEW {@code TODO_SUSPECTED_GAP} entry should be resolved before that controller
 *   ships — leaving the marker indefinitely defeats the test's audit purpose.
 *
 * State: Stateless. Pure-classpath unit test. Sibling to {@link ControllerContractArchTest}
 *   and {@link AdminEndpointCoverageArchTest} — same scanning pattern, same ratchet-down
 *   semantics.
 */
public class ControllerPreAuthorizeArchTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.operativus.agentmanager";

    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, DeleteMapping.class, PatchMapping.class);

    /**
     * Controllers where the class declaration carries {@code @PreAuthorize}. Every handler
     * inherits the gate. Removal of the class-level annotation silently widens access to
     * "any authenticated user" — fails the build here.
     */
    private static final Set<String> FULLY_CLASS_GATED = new TreeSet<>(Set.of(
            "AgentAdminController",                     // gated by PR #969 — closes TODO_SUSPECTED_GAP surfaced by this test in PR #967
            "ApprovalsController",
            "AuditLogController",
            "ComposioAdminController",
            "ComposioCatalogController",
            "ComposioConfigDriftController",
            "ComposioConnectionAdminController",
            "DataRetentionController",
            "EmbeddingBackfillAdminController",
            "EscalationsController",
            "FinOpsAdminController",
            "JobsController",
            "ModelCatalogController",
            "MonitoringController",
            "PiiAdminController",                       // gated by PR #968 — closes TODO_SUSPECTED_GAP surfaced by this test in PR #967
            "ProviderCredentialAdminController",
            "RoutingDecisionAdminController",
            "RoutingEmbeddingsAdminController",
            "SystemAuditLogController",
            "UserAdminController",
            "WorkflowsController"));

    /**
     * Controllers with NO class-level {@code @PreAuthorize} but EVERY method individually
     * gated. Value = expected endpoint count = expected method-authz count. Either dropping
     * a method-level gate or adding a new ungated method fails the build for this set.
     */
    private static final Map<String, Integer> ALL_METHODS_GATED_NO_CLASS = new TreeMap<>(Map.ofEntries(
            Map.entry("BackgroundJobController", 6),
            Map.entry("ComplianceController", 4),
            Map.entry("IncidentResponseController", 3),
            Map.entry("SecurityInterceptsController", 1),
            Map.entry("SloController", 1)));

    /**
     * Controllers with no class-level {@code @PreAuthorize} where SOME but not all methods
     * are method-gated. Value = exact count of method-level {@code @PreAuthorize} present
     * today. Tracking the count (not method names) keeps the test resilient to method
     * renames while still surfacing additions/removals.
     *
     * <p><b>MAINTENANCE CONTRACT — read before editing a controller's annotations.</b>
     * If a PR adds or removes a method-level {@code @PreAuthorize} on a controller listed
     * here, that PR MUST also update the controller's entry in this map (or move the
     * controller to {@link #ALL_METHODS_GATED_NO_CLASS} / {@link #FULLY_CLASS_GATED} if
     * the change reaches a uniform state). Otherwise the build fails at
     * {@code allMixedControllersMatchPinnedAuthzCount}. PR #1020 hit this trap silently
     * in local dev (the failure only surfaces with a full {@code ./mvnw test}) — caught
     * pre-push by chance. Treat the count update as part of the change, not a follow-up.
     */
    private static final Map<String, Integer> MIXED_METHOD_AUTHZ_COUNT = new TreeMap<>(Map.ofEntries(
            // 4 of 5 endpoints gated after PR #1020 (was 1 of 5 — only /test). create/update/delete
            // are now @PreAuthorize("hasRole('ADMIN')"); list() remains open to tenant members.
            Map.entry("AlertIntegrationController", 4),
            Map.entry("AlertingController", 4),
            Map.entry("AuthController", 1),               // 1 of 4 — login/register/refresh are public per app.security.public-paths
            Map.entry("EvaluationController", 5),
            Map.entry("ExtensionController", 4),
            Map.entry("McpLifecycleController", 1),
            Map.entry("MemoryController", 3),
            Map.entry("SchedulesController", 5),
            Map.entry("SettingsController", 1)));

    /**
     * Controllers with no annotations of their own that are nonetheless admin-gated because
     * every handler delegates to a service whose method carries
     * {@code @PreAuthorize("hasRole('ADMIN')")}. Spring Security throws
     * {@code AccessDeniedException} before the service body runs — equivalent in practice
     * to a class-level controller annotation, just placed one frame deeper.
     *
     * <p>Value = simple name of the service impl class. The test verifies that the impl
     * declares at least as many admin-gated methods as the controller has endpoints, which
     * is sufficient positive evidence that every handler dispatches through a gated call.
     * Stronger guarantees (per-handler call-graph mapping) would require bytecode analysis
     * and are out of scope.
     *
     * <p>Removing {@code @PreAuthorize} from any service method on the impl narrows the
     * count and breaks the test, forcing investigation. Adding new ungated public methods
     * to the impl does not break the test as long as the count still meets the endpoint
     * floor — those new methods may be internal helpers (e.g. {@code ModelService.pingEntity},
     * {@code ModelService.getModelEntityById}) not reachable from the controller.
     */
    private static final Map<String, String> SERVICE_LAYER_GATED = new TreeMap<>(Map.ofEntries(
            // audited PR #971 → moved out of INTENTIONALLY_UNANNOTATED. Every ModelService
            // method called from ModelController (getAllModels/createModel/updateModel/
            // cloneModel/deleteModel/testConnection/pingExistingModel/clearRateLimit)
            // carries @PreAuthorize("hasRole('ADMIN')"). Future improvement: lift the
            // annotations to ModelController class-level to match the *AdminController pattern.
            Map.entry("ModelController", "ModelService")));

    /** SpEL fragment matcher for {@code hasRole('ADMIN')} / {@code hasAuthority('ROLE_ADMIN')}. */
    private static final Pattern ADMIN_GATE_SPEL = Pattern.compile(
            "hasRole\\(\\s*['\"]ADMIN['\"]\\s*\\)"
                    + "|hasAuthority\\(\\s*['\"]ROLE_ADMIN['\"]\\s*\\)");

    /**
     * Controllers with NO {@code @PreAuthorize} annotations anywhere (class or method).
     * Endpoints rely on Spring Security's chain-level {@code .authenticated()} default plus
     * service-layer tenant scoping. Each entry should fit one of these rationales:
     *
     * <ul>
     *   <li><b>tenant-scoped:</b> service layer enforces {@code AgentContextHolder.getOrgId()};
     *       any authenticated user in the org can call.</li>
     *   <li><b>self-scoped:</b> endpoint operates on resources owned by the caller (e.g.
     *       agent runs, sessions, knowledge bases).</li>
     *   <li><b>read-only diagnostic:</b> observability/diagnostics surfaces — value of role
     *       gate is debatable for read-only telemetry.</li>
     *   <li><b>TODO_SUSPECTED_GAP:</b> the class name ({@code *Admin}) suggests a role gate
     *       may be missing. Each should be triaged independently.</li>
     * </ul>
     */
    private static final Set<String> INTENTIONALLY_UNANNOTATED = new TreeSet<>(Set.of(
            // === Tenant- or self-scoped — service layer enforces orgId/userId filtering ===
            "A2AController",                  // tenant-scoped: A2A peer registration scoped by orgId
            "A2aHealthController",            // permit-all candidate: A2A health probe
            "AgentCredentialController",      // self-scoped: per-user credential resources
            "AgentEventSseController",        // tenant-scoped: caller's own agent events (AgentContextHolder.getOrgId())
            "AgentReflectionController",      // tenant-scoped: agent self-reflection on caller's agents
            "AgenticMemoryController",        // tenant-scoped: memory CRUD on caller's agents
            "AgentsController",               // tenant-scoped: list/run own agents
            "BudgetExceededController",       // tenant-scoped: FinOps signals for caller's org
            "ConfigController",               // tenant-scoped: per-org configuration reads
            "DelegationTopologyController",   // read-only diagnostic
            "DiagnosticsController",          // read-only diagnostic
            "KnowledgeBaseController",        // tenant-scoped: KB CRUD scoped to caller's orgId
            "KnowledgeController",            // tenant-scoped: ingest/search/list owned KBs
            "KnowledgePreviewController",     // tenant-scoped: preview rendering on owned docs
            "McpController",                  // tenant-scoped: MCP tool surface for caller
            "MemoryTaggingController",        // tenant-scoped: memory tag operations
            "ObservabilityController",        // read-only diagnostic
            "OrchestrationAggregateController", // read-only diagnostic
            "OrgEventSseController",          // tenant-scoped: caller's org-wide events (AgentContextHolder.getOrgId())
            "RegistryController",             // tenant-scoped: agent registry reads
            "RunEventSseController",          // self-scoped: caller's own run events
            "RunTelemetryController",         // self-scoped: caller's own run telemetry
            "RunsController",                 // self-scoped: caller's own runs
            "SafetyAggregateController",      // read-only diagnostic
            "SessionAggregateController",     // read-only diagnostic
            "SessionController",              // self-scoped: caller's own sessions
            "SseTokenController",             // self-scoped: caller's own SSE token issuance
            "TeamsController",                // audited PR #970: tenant-scoped (every TeamService method uses callerOrgId() — getAllTeams/searchTeams/getTeamById/createTeam/updateTeam/deleteTeam/archiveTeam/restoreTeam/cloneTeam/getTeamHealth + all members/edges paths)
            "ToolAggregateController",        // read-only diagnostic
            "ToolController"));                // tenant-scoped: tool listing for caller
            // === Suspected-gap entries closed in earlier PRs ===
            // PiiAdminController — closed by PR #968 (now in FULLY_CLASS_GATED)
            // AgentAdminController — closed by PR #969 (now in FULLY_CLASS_GATED)

    @Test
    void fullyClassGatedControllers_haveClassLevelPreAuthorize() {
        TreeSet<String> observed = new TreeSet<>();
        for (Class<?> controller : findControllers()) {
            if (hasEndpoints(controller) && controller.isAnnotationPresent(PreAuthorize.class)) {
                observed.add(controller.getSimpleName());
            }
        }
        if (!observed.equals(FULLY_CLASS_GATED)) {
            TreeSet<String> added = new TreeSet<>(observed);
            added.removeAll(FULLY_CLASS_GATED);
            TreeSet<String> removed = new TreeSet<>(FULLY_CLASS_GATED);
            removed.removeAll(observed);
            fail("FULLY_CLASS_GATED drift:\n"
                    + "  newly class-gated (add to FULLY_CLASS_GATED): " + added + "\n"
                    + "  no longer class-gated (REMOVAL OF AUTHZ — investigate): " + removed);
        }
    }

    @Test
    void allMethodsGatedNoClass_methodAuthzCounts_areStable() {
        Map<String, Integer> observed = new TreeMap<>();
        for (Class<?> controller : findControllers()) {
            if (controller.isAnnotationPresent(PreAuthorize.class)) continue;
            int endpoints = 0;
            int methodAuthz = 0;
            for (Method m : controller.getDeclaredMethods()) {
                if (!isMappingMethod(m)) continue;
                endpoints++;
                if (m.isAnnotationPresent(PreAuthorize.class)) methodAuthz++;
            }
            if (endpoints > 0 && methodAuthz == endpoints) {
                observed.put(controller.getSimpleName(), endpoints);
            }
        }
        if (!observed.equals(ALL_METHODS_GATED_NO_CLASS)) {
            failOnMapDrift("ALL_METHODS_GATED_NO_CLASS", ALL_METHODS_GATED_NO_CLASS, observed,
                    "An ungated method may have been added to a previously-fully-gated controller.");
        }
    }

    @Test
    void mixedControllers_methodLevelAuthzCounts_areStable() {
        Map<String, Integer> observed = new TreeMap<>();
        for (Class<?> controller : findControllers()) {
            if (controller.isAnnotationPresent(PreAuthorize.class)) continue;
            int endpoints = 0;
            int methodAuthz = 0;
            for (Method m : controller.getDeclaredMethods()) {
                if (!isMappingMethod(m)) continue;
                endpoints++;
                if (m.isAnnotationPresent(PreAuthorize.class)) methodAuthz++;
            }
            if (methodAuthz > 0 && methodAuthz < endpoints) {
                observed.put(controller.getSimpleName(), methodAuthz);
            }
        }
        if (!observed.equals(MIXED_METHOD_AUTHZ_COUNT)) {
            failOnMapDrift("MIXED_METHOD_AUTHZ_COUNT", MIXED_METHOD_AUTHZ_COUNT, observed,
                    "A count BELOW pinned typically means a method-level @PreAuthorize was removed "
                            + "(SECURITY REGRESSION — investigate). A count ABOVE pinned means a "
                            + "method-level gate was added (usually intentional — update the count).");
        }
    }

    @Test
    void intentionallyUnannotatedControllers_remainUnannotated() {
        TreeSet<String> observed = new TreeSet<>();
        for (Class<?> controller : findControllers()) {
            if (controller.isAnnotationPresent(PreAuthorize.class)) continue;
            String name = controller.getSimpleName();
            if (SERVICE_LAYER_GATED.containsKey(name)) continue;
            int endpoints = 0;
            int methodAuthz = 0;
            for (Method m : controller.getDeclaredMethods()) {
                if (!isMappingMethod(m)) continue;
                endpoints++;
                if (m.isAnnotationPresent(PreAuthorize.class)) methodAuthz++;
            }
            if (endpoints > 0 && methodAuthz == 0) {
                observed.add(name);
            }
        }
        if (!observed.equals(INTENTIONALLY_UNANNOTATED)) {
            TreeSet<String> added = new TreeSet<>(observed);
            added.removeAll(INTENTIONALLY_UNANNOTATED);
            TreeSet<String> removed = new TreeSet<>(INTENTIONALLY_UNANNOTATED);
            removed.removeAll(observed);
            fail("INTENTIONALLY_UNANNOTATED drift:\n"
                    + "  newly unannotated (add to INTENTIONALLY_UNANNOTATED with a rationale "
                    + "comment — see existing entries for the format, or place into "
                    + "SERVICE_LAYER_GATED if a service-layer admin gate covers all handlers): "
                    + added + "\n"
                    + "  no longer unannotated (a @PreAuthorize was ADDED — remove from this set "
                    + "and place into FULLY_CLASS_GATED / ALL_METHODS_GATED_NO_CLASS / "
                    + "MIXED_METHOD_AUTHZ_COUNT as appropriate): " + removed);
        }
    }

    @Test
    void serviceLayerGatedControllers_haveAdminGatedServiceImpl() {
        Map<String, Class<?>> controllersByName = new TreeMap<>();
        for (Class<?> controller : findControllers()) {
            if (hasEndpoints(controller)) {
                controllersByName.put(controller.getSimpleName(), controller);
            }
        }
        for (Map.Entry<String, String> entry : SERVICE_LAYER_GATED.entrySet()) {
            String controllerName = entry.getKey();
            String serviceName = entry.getValue();

            Class<?> controller = controllersByName.get(controllerName);
            if (controller == null) {
                fail("SERVICE_LAYER_GATED entry refers to unknown controller " + controllerName
                        + " — was the class renamed or deleted? Update or remove the entry.");
            }
            if (controller.isAnnotationPresent(PreAuthorize.class)
                    || hasAnyMethodLevelPreAuthorize(controller)) {
                fail(controllerName + " is on SERVICE_LAYER_GATED but now declares its own "
                        + "@PreAuthorize (class- or method-level). Move it to FULLY_CLASS_GATED / "
                        + "ALL_METHODS_GATED_NO_CLASS / MIXED_METHOD_AUTHZ_COUNT and delete the "
                        + "SERVICE_LAYER_GATED entry — the service-layer fallback is no longer "
                        + "the load-bearing gate.");
            }

            Class<?> serviceImpl = findClassByName(CONTROLLER_BASE_PACKAGE, serviceName);
            if (serviceImpl == null) {
                fail("SERVICE_LAYER_GATED entry for " + controllerName + " names service "
                        + serviceName + " which was not found under " + CONTROLLER_BASE_PACKAGE
                        + ". Renamed? Deleted? Update the manifest entry.");
            }

            long endpointCount = Arrays.stream(controller.getDeclaredMethods())
                    .filter(ControllerPreAuthorizeArchTest::isMappingMethod)
                    .count();
            long adminGatedServiceMethods = Arrays.stream(serviceImpl.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
                    .filter(m -> ADMIN_GATE_SPEL.matcher(
                            m.getAnnotation(PreAuthorize.class).value()).find())
                    .count();
            if (adminGatedServiceMethods < endpointCount) {
                fail(controllerName + " is on SERVICE_LAYER_GATED → " + serviceName
                        + ", but " + serviceName + " has only " + adminGatedServiceMethods
                        + " admin-gated method(s) for " + endpointCount + " endpoint(s). "
                        + "A handler may now dispatch through an unguarded service call "
                        + "(SECURITY REGRESSION — investigate which @PreAuthorize was removed).");
            }
        }
    }

    @Test
    void everyMappingController_fitsExactlyOneBucket() {
        int totalControllers = 0;
        for (Class<?> controller : findControllers()) {
            if (hasEndpoints(controller)) totalControllers++;
        }
        int bucketed = FULLY_CLASS_GATED.size()
                + ALL_METHODS_GATED_NO_CLASS.size()
                + MIXED_METHOD_AUTHZ_COUNT.size()
                + SERVICE_LAYER_GATED.size()
                + INTENTIONALLY_UNANNOTATED.size();
        assertEquals(totalControllers, bucketed,
                "Bucket totals must equal total controller count. A controller is missing "
                        + "from exactly one bucket — check the five drift messages above.");
    }

    private static boolean hasEndpoints(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (isMappingMethod(m)) return true;
        }
        return false;
    }

    private static List<Class<?>> findControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> result = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE)) {
            try {
                result.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Could not load controller " + bd.getBeanClassName(), e);
            }
        }
        return result;
    }

    private static boolean isMappingMethod(Method m) {
        for (Class<? extends Annotation> a : MAPPING_ANNOTATIONS) {
            if (m.isAnnotationPresent(a)) return true;
        }
        return false;
    }

    private static boolean hasAnyMethodLevelPreAuthorize(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.isAnnotationPresent(PreAuthorize.class)) return true;
        }
        return false;
    }

    /**
     * Finds a class by simple name under the given base package. Used only by
     * {@link #serviceLayerGatedControllers_haveAdminGatedServiceImpl()} — the
     * {@code SERVICE_LAYER_GATED} manifest is small, so the O(N) scan is fine.
     */
    private static Class<?> findClassByName(String basePackage, String simpleName) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                        return true;
                    }
                };
        scanner.addIncludeFilter((mr, mrf) ->
                simpleName.equals(mr.getClassMetadata().getClassName().substring(
                        mr.getClassMetadata().getClassName().lastIndexOf('.') + 1)));
        for (var bd : scanner.findCandidateComponents(basePackage)) {
            try {
                return Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Could not load " + bd.getBeanClassName(), e);
            }
        }
        return null;
    }

    private static void failOnMapDrift(String label, Map<String, Integer> pinned,
                                       Map<String, Integer> observed, String hint) {
        Map<String, String> diff = new LinkedHashMap<>();
        TreeSet<String> allKeys = new TreeSet<>();
        allKeys.addAll(pinned.keySet());
        allKeys.addAll(observed.keySet());
        for (String k : allKeys) {
            Integer p = pinned.get(k);
            Integer a = observed.get(k);
            if (!java.util.Objects.equals(p, a)) {
                diff.put(k, "pinned=" + p + " actual=" + a);
            }
        }
        fail(label + " drift:\n  " + diff + "\n\n  " + hint);
    }
}
