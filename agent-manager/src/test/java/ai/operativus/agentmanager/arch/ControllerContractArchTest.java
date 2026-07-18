package ai.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Forward guard against schema-erasing types on the BOTH SIDES of
 *   the REST controller contract:
 *   <ol>
 *     <li><b>Return types</b> — {@code Map<String,Object>}, {@code Map<String,?>},
 *         {@code Object}, {@code ResponseEntity<?>}, {@code ResponseEntity<Map<String,Object>>},
 *         {@code List<Map<String,Object>>}. Every such return wipes the wire-shape contract —
 *         frontend consumers must hand-write a record type per call, and a backend rename
 *         will not surface as a compile error on the consumer.</li>
 *     <li><b>{@code @RequestBody} parameter types</b> — {@code Map<String,Object>},
 *         {@code Map<String,?>}, {@code Map<String,String>}, {@code Map<String,Integer>},
 *         {@code Object}. Inbound bodies as raw maps cannot be {@code @Valid}-ated, have no
 *         documented schema, and silently accept any keys. STRICTER than return-type rule:
 *         even {@code Map<String,String>} is flagged for inbound (a typed record is the only
 *         contract surface that gives the handler presence + type guarantees).</li>
 *   </ol>
 *   Each side has its own allowlist; promotions ratchet down the count via the stale-entry
 *   guard. New violators on either side fail the build.
 * State: Stateless. Pure-classpath unit test — does NOT boot the Spring context, does
 *   NOT require Postgres, does NOT use {@code BaseIntegrationTest}. Runs as part of
 *   {@code ./mvnw test} (no {@code -Dgroups=integration} required).
 *
 * Why classpath scanning instead of {@code RequestMappingHandlerMapping}:
 *   The previous incarnation extended {@link ai.operativus.agentmanager.integration.BaseIntegrationTest}
 *   which boots a Postgres testcontainer and the full Spring context just to walk the
 *   handler map. That's a 30+ second penalty for what is fundamentally a static-analysis
 *   check. {@link ClassPathScanningCandidateComponentProvider} finds all controller
 *   beans by annotation; reflection on each class enumerates the {@code @*Mapping}
 *   methods. No bean wiring, no DB, no HTTP — just classpath introspection.
 *
 * Why not ArchUnit:
 *   ArchUnit 1.3.0 cannot read Java 25 classfiles (see {@link ai.operativus.agentmanager.ModulithArchitectureTest}
 *   which is @Disabled for the same reason). Reflection sidesteps the classfile-format
 *   dependency.
 *
 * Allowlist discipline:
 *   - Pre-existing violators are listed in {@link #ALLOWLIST}; the build is GREEN
 *     while every violator is listed.
 *   - Any controller method that violates the rule and is NOT on the allowlist
 *     fails the build (forward guard).
 *   - Any allowlist entry that no longer matches any live controller method also
 *     fails the build (stale-entry guard) — Phase 3 of the API-contract plan uses
 *     this to ratchet violators down: every record promotion MUST remove its
 *     allowlist entry.
 *   - Each entry tagged "// TODO: Phase 3 / future" — review guidance is to fix
 *     the violator (promote to a typed record) rather than extend the allowlist;
 *     allowlist additions require explicit PR-description justification.
 */
public class ControllerContractArchTest {

    private static final String CONTROLLER_BASE_PACKAGE = "ai.operativus.agentmanager";

    /**
     * Pre-existing violators of the schema-erasing-RETURN-type rule.
     * Format: "fully.qualified.ClassName#methodName".
     *
     * Count (33) reconciliation note:
     *   docs/analysis/agm-ui-api-sync.md §"BE Map/Object Returns" lists 46 BE Map/Object
     *   returns — that figure includes typed maps like Map&lt;String,String&gt;,
     *   Map&lt;JobStatus,Long&gt;, and Map&lt;String,Integer&gt; which are TIGHTER contracts
     *   and are intentionally NOT flagged by this arch test. This arch test only fails on
     *   the genuinely schema-erasing patterns enumerated in {@link #classifyReturn(Type)}:
     *     - Map&lt;String,Object&gt; / Map&lt;String,?&gt;          (Object value or unbounded ?)
     *     - List&lt;Map&lt;String,Object&gt;&gt;                    (one-level descent)
     *     - ResponseEntity&lt;?&gt;                            (wildcard)
     *     - ResponseEntity&lt;Map&lt;String,Object&gt;&gt; / &lt;?&gt;     (same Map rules inside RE)
     *     - ResponseEntity&lt;List&lt;Map&lt;String,Object&gt;&gt;&gt;     (one-level descent inside RE)
     *     - bare Object                                         (no schema at all)
     *   Typed maps still warrant promotion but reach a hand-typed FE consumer alignment —
     *   they're "drift-prone" rather than "no-schema", and the analysis docs them in a
     *   separate table that this test does not enforce on the return-type side.
     */
    private static final Set<String> RETURN_TYPE_ALLOWLIST = Set.of(
            // AuthController — both are ResponseEntity<?>; success body is JwtResponse,
            // failure body is ErrorResponse. Promote to a sealed ApiAuthResponse hierarchy or
            // split @ExceptionHandler so the success path returns ResponseEntity<JwtResponse>.
            "ai.operativus.agentmanager.control.controller.AuthController#authenticateUser",      // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.AuthController#registerUser",          // TODO: Phase 3 / future

            // ComplianceController — long-tail per traffic ranking; deferred from Phase 3.
            "ai.operativus.agentmanager.control.controller.ComplianceController#exportUserData",  // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.ComplianceController#eraseUserData",   // TODO: Phase 3 / future

            // ConfigController — long-tail; deferred.
            "ai.operativus.agentmanager.control.controller.ConfigController#getConfig",           // TODO: Phase 3 / future

            // DiagnosticsController — admin diagnostic; long-tail.
            "ai.operativus.agentmanager.control.controller.DiagnosticsController#currentThread",  // TODO: Phase 3 / future

            // KnowledgeBaseController + KnowledgeController + KnowledgePreviewController — long-tail.
            "ai.operativus.agentmanager.control.controller.KnowledgeController#getChunks",                 // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.KnowledgePreviewController#previewDocument",    // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.KnowledgePreviewController#getChunkDetails",    // TODO: Phase 3 / future

            // McpController + McpLifecycleController — long-tail; admin-only diagnostics.
            "ai.operativus.agentmanager.control.controller.McpController#handleMessage",          // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.McpLifecycleController#getStatus",     // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.McpLifecycleController#listServers",   // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.McpLifecycleController#reconnectServer", // TODO: Phase 3 / future

            // MemoryController + MemoryTaggingController — long-tail.
            "ai.operativus.agentmanager.control.controller.MemoryController#getMemoryStats",      // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.MemoryTaggingController#getTimeline",  // TODO: Phase 3 / future

            // MonitoringController + ObservabilityController — admin diagnostics.
            "ai.operativus.agentmanager.control.controller.MonitoringController#getGlobalStats",  // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.ObservabilityController#getOtlpConfig",// TODO: Phase 3 / future

            // TeamsController — two ResponseEntity<?> on bulk + edge ops.
            "ai.operativus.agentmanager.control.controller.TeamsController#bulkAddMembers",       // TODO: Phase 3 / future
            "ai.operativus.agentmanager.control.controller.TeamsController#addTransitionEdge"    // TODO: Phase 3 / future
    );

    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class,
            GetMapping.class,
            PostMapping.class,
            PutMapping.class,
            DeleteMapping.class,
            PatchMapping.class);

    /**
     * Pre-existing violators of the schema-erasing-{@code @RequestBody}-type rule.
     * Format: "fully.qualified.ClassName#methodName".
     *
     * <p>Inbound rule is STRICTER than the return-type rule: a request body typed as
     * {@code Map<String, ?>} (any value type, not just {@code Object}) cannot be
     * {@code @Valid}-ated, has no documented schema, and silently accepts any keys.
     * Promote inbound bodies to typed records eagerly — there is no "consumer-driven
     * promotion" carve-out that exists for the return side.
     */
    private static final Set<String> REQUEST_BODY_ALLOWLIST = Set.of(
            // ARCH NOTE: documented exception — do NOT promote. SettingsService.updateSettings
            // iterates a genuinely dynamic key-space (constants + user-defined). Promoting to
            // a fixed-field record breaks the dynamic-key behavior; wrapping the map is the
            // §v3-A wrapper-record anti-pattern. See SettingsController.updateSettings Javadoc.
            "ai.operativus.agentmanager.control.controller.SettingsController#updateSettings"        // ARCH NOTE: documented exception, do not promote
    );

    @Test
    void controllerHandlersDoNotReturnSchemaErasingTypes() {
        List<String> violations = new ArrayList<>();
        for (Method method : findAllMappedHandlerMethods()) {
            ViolationKind kind = classifyReturn(method.getGenericReturnType());
            if (kind == ViolationKind.NONE) {
                continue;
            }
            String key = method.getDeclaringClass().getName() + "#" + method.getName();
            if (RETURN_TYPE_ALLOWLIST.contains(key)) {
                continue;
            }
            violations.add(key + "  ⟶  " + kind.describe());
        }

        if (!violations.isEmpty()) {
            String report = violations.stream().sorted().collect(Collectors.joining("\n  - ", "  - ", ""));
            fail("""
                    Controller handlers must not return schema-erasing types.
                    Found %d violation(s) not listed in RETURN_TYPE_ALLOWLIST. Promote each to a typed record
                    (or add to RETURN_TYPE_ALLOWLIST with a "// TODO: Phase 3 / future" tag and PR-description
                    justification).
                    %s""".formatted(violations.size(), report));
        }
    }

    /**
     * Soundness pin — fails if a Phase-3 promotion landed without removing the corresponding
     * allowlist entry. Stale allowlist entries silently shield the build from future regressions
     * that re-introduce the same violator at a different site.
     */
    @Test
    void returnTypeAllowlistDoesNotContainStaleEntries() {
        Set<String> liveViolators = new TreeSet<>();
        for (Method method : findAllMappedHandlerMethods()) {
            if (classifyReturn(method.getGenericReturnType()) != ViolationKind.NONE) {
                liveViolators.add(method.getDeclaringClass().getName() + "#" + method.getName());
            }
        }
        Set<String> stale = new TreeSet<>(RETURN_TYPE_ALLOWLIST);
        stale.removeAll(liveViolators);
        assertTrue(stale.isEmpty(),
                "RETURN_TYPE_ALLOWLIST contains entries that no longer match any controller method "
                        + "(method renamed, return type fixed, or controller deleted). Remove:\n  - "
                        + String.join("\n  - ", stale));
    }

    @Test
    void controllerHandlersDoNotAcceptSchemaErasingRequestBodies() {
        List<String> violations = new ArrayList<>();
        for (Method method : findAllMappedHandlerMethods()) {
            String violation = classifyRequestBodyOrNull(method);
            if (violation == null) {
                continue;
            }
            String key = method.getDeclaringClass().getName() + "#" + method.getName();
            if (REQUEST_BODY_ALLOWLIST.contains(key)) {
                continue;
            }
            violations.add(key + "  ⟶  " + violation);
        }

        if (!violations.isEmpty()) {
            String report = violations.stream().sorted().collect(Collectors.joining("\n  - ", "  - ", ""));
            fail("""
                    Controller handlers must not accept schema-erasing @RequestBody types.
                    Found %d violation(s) not listed in REQUEST_BODY_ALLOWLIST. Promote each @RequestBody
                    to a typed record (or add to REQUEST_BODY_ALLOWLIST with a TODO tag and PR-description
                    justification — the inbound rule is stricter than the return-side rule because
                    typed records are the only contract surface that gives the handler @Valid + presence guarantees).
                    %s""".formatted(violations.size(), report));
        }
    }

    /**
     * Soundness pin for the inbound side — fails if a request-body promotion landed without
     * removing the corresponding allowlist entry.
     */
    @Test
    void requestBodyAllowlistDoesNotContainStaleEntries() {
        Set<String> liveViolators = new TreeSet<>();
        for (Method method : findAllMappedHandlerMethods()) {
            if (classifyRequestBodyOrNull(method) != null) {
                liveViolators.add(method.getDeclaringClass().getName() + "#" + method.getName());
            }
        }
        Set<String> stale = new TreeSet<>(REQUEST_BODY_ALLOWLIST);
        stale.removeAll(liveViolators);
        assertTrue(stale.isEmpty(),
                "REQUEST_BODY_ALLOWLIST contains entries that no longer match any controller @RequestBody "
                        + "(method renamed, body type fixed, or controller deleted). Remove:\n  - "
                        + String.join("\n  - ", stale));
    }

    private static List<Method> findAllMappedHandlerMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<Method> methods = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE)) {
            String fqcn = bd.getBeanClassName();
            if (fqcn == null) {
                continue;
            }
            Class<?> controllerClass;
            try {
                controllerClass = Class.forName(fqcn);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Classpath scanner found %s but reflection cannot load it".formatted(fqcn), e);
            }
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (hasMappingAnnotation(method)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static boolean hasMappingAnnotation(Method method) {
        for (Class<? extends Annotation> ann : MAPPING_ANNOTATIONS) {
            if (method.isAnnotationPresent(ann)) {
                return true;
            }
        }
        return false;
    }

    private static ViolationKind classifyReturn(Type returnType) {
        if (returnType == Object.class) {
            return ViolationKind.BARE_OBJECT;
        }
        if (returnType instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            Type[] args = pt.getActualTypeArguments();

            if (raw == ResponseEntity.class && args.length == 1) {
                Type inner = args[0];
                if (inner instanceof WildcardType) {
                    return ViolationKind.RESPONSE_ENTITY_WILDCARD;
                }
                if (isMapStringObjectLike(inner)) {
                    return ViolationKind.RESPONSE_ENTITY_MAP_STRING_OBJECT;
                }
                if (inner instanceof ParameterizedType innerPt
                        && (innerPt.getRawType() == List.class
                                || innerPt.getRawType() == Collection.class
                                || innerPt.getRawType() == Iterable.class)
                        && innerPt.getActualTypeArguments().length == 1
                        && isMapStringObjectLike(innerPt.getActualTypeArguments()[0])) {
                    return ViolationKind.RESPONSE_ENTITY_LIST_MAP_STRING_OBJECT;
                }
            }

            if (isMapStringObjectLike(returnType)) {
                return ViolationKind.MAP_STRING_OBJECT;
            }

            if ((raw == List.class || raw == Collection.class || raw == Iterable.class)
                    && args.length == 1
                    && isMapStringObjectLike(args[0])) {
                return ViolationKind.LIST_MAP_STRING_OBJECT;
            }
        }
        return ViolationKind.NONE;
    }

    private static boolean isMapStringObjectLike(Type t) {
        if (!(t instanceof ParameterizedType pt)) {
            return false;
        }
        if (pt.getRawType() != Map.class) {
            return false;
        }
        Type[] args = pt.getActualTypeArguments();
        if (args.length != 2 || args[0] != String.class) {
            return false;
        }
        Type valueArg = args[1];
        if (valueArg == Object.class) {
            return true;
        }
        if (valueArg instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            return upper.length == 1 && upper[0] == Object.class;
        }
        return false;
    }

    /**
     * Inbound classifier — STRICTER than {@link #classifyReturn(Type)}: any
     * {@code Map<String, *>} (regardless of value type) is flagged. Returns {@code null}
     * if no @RequestBody parameter exists or the type is acceptable; otherwise returns
     * a one-line description of the violation.
     */
    private static String classifyRequestBodyOrNull(Method method) {
        for (Parameter p : method.getParameters()) {
            if (!p.isAnnotationPresent(RequestBody.class)) {
                continue;
            }
            Type t = p.getParameterizedType();
            if (t == Object.class) {
                return "@RequestBody Object (no schema)";
            }
            if (t instanceof ParameterizedType pt && pt.getRawType() == Map.class) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length == 2 && args[0] == String.class) {
                    Type valueArg = args[1];
                    if (valueArg == Object.class) {
                        return "@RequestBody Map<String, Object> (no schema, no @Valid possible)";
                    }
                    if (valueArg instanceof WildcardType) {
                        return "@RequestBody Map<String, ?> (no schema, no @Valid possible)";
                    }
                    return "@RequestBody Map<String, " + describeSimple(valueArg) + "> (loose; promote to typed record)";
                }
                return "@RequestBody raw Map (no schema, no @Valid possible)";
            }
        }
        return null;
    }

    private static String describeSimple(Type t) {
        if (t instanceof Class<?> c) {
            return c.getSimpleName();
        }
        return t.getTypeName();
    }

    private enum ViolationKind {
        NONE,
        BARE_OBJECT,
        MAP_STRING_OBJECT,
        LIST_MAP_STRING_OBJECT,
        RESPONSE_ENTITY_WILDCARD,
        RESPONSE_ENTITY_MAP_STRING_OBJECT,
        RESPONSE_ENTITY_LIST_MAP_STRING_OBJECT;

        String describe() {
            return switch (this) {
                case NONE -> "OK";
                case BARE_OBJECT -> "returns Object (no schema)";
                case MAP_STRING_OBJECT -> "returns Map<String,?> / Map<String,Object> (no schema)";
                case LIST_MAP_STRING_OBJECT -> "returns List<Map<String,?>> (no schema)";
                case RESPONSE_ENTITY_WILDCARD -> "returns ResponseEntity<?> (no schema)";
                case RESPONSE_ENTITY_MAP_STRING_OBJECT -> "returns ResponseEntity<Map<String,?>> (no schema)";
                case RESPONSE_ENTITY_LIST_MAP_STRING_OBJECT -> "returns ResponseEntity<List<Map<String,?>>> (no schema)";
            };
        }
    }
}
