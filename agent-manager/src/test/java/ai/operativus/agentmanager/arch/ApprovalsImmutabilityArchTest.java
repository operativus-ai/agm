package ai.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Forward guard locking the {@code ApprovalsController} HTTP surface
 *   shape. Approval rows are append-only post-creation by design — the only allowed
 *   mutations are POST {@code /{id}/resolve} and POST {@code /bulk-resolve}, both of which
 *   transition PENDING rows to a terminal state via {@code ApprovalService}. Reintroducing
 *   a PUT/PATCH/DELETE on this controller would open an out-of-band mutation path and
 *   bypass the resolve-decision audit trail (payload_hash check, approver list check,
 *   APPROVAL_SLA_BREACH event, decision_tier preservation).
 * State: Stateless. Pure-classpath unit test. Does NOT boot Spring, does NOT require
 *   Postgres, does NOT use {@code BaseIntegrationTest}. Runs as part of
 *   {@code ./mvnw test} (no {@code -Dgroups=integration} required).
 *
 * Why reflection instead of ArchUnit: ArchUnit 1.3.0 cannot read Java 25 classfiles
 *   — see the explanatory block in {@link ControllerContractArchTest}.
 *
 * Forward-guard discipline: if a future change adds a PUT/PATCH/DELETE on
 *   {@code ApprovalsController}, OR adds a POST outside the {@link #ALLOWED_POST_PATHS}
 *   set, this test fails. The contract is intentionally tight; if a new mutation path
 *   is genuinely required, update {@link #ALLOWED_POST_PATHS} in the same PR with a
 *   PR-description justification.
 */
public class ApprovalsImmutabilityArchTest {

    private static final String APPROVALS_CONTROLLER_FQCN =
            "ai.operativus.agentmanager.control.controller.ApprovalsController";

    /**
     * Post-creation mutation surface on {@code ApprovalsController}. Path values are the
     * relative segment under the class-level {@code @RequestMapping("/api/v1/approvals")}.
     * Order-insensitive; missing entries fail the build (the symmetric "stale" check
     * fails if an allowlist entry no longer matches any handler).
     */
    private static final Set<String> ALLOWED_POST_PATHS = Set.of(
            "/{id}/resolve",
            "/bulk-resolve",
            // REQ-HR-5 — unified decide endpoint. Operates on human_review_pending rows
            // (not the legacy Approval table) but lives on this controller so all
            // operator-facing approval mutations share one URL prefix.
            "/{id}/decide"
    );

    private static final List<Class<? extends Annotation>> MUTATING_NON_POST_ANNOTATIONS = List.of(
            PutMapping.class,
            PatchMapping.class,
            DeleteMapping.class
    );

    private static Class<?> loadApprovalsController() {
        try {
            return Class.forName(APPROVALS_CONTROLLER_FQCN);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "ApprovalsController not on classpath at " + APPROVALS_CONTROLLER_FQCN
                            + " — was it renamed or deleted? Update this arch test in the same PR.",
                    e);
        }
    }

    @Test
    void approvalsController_hasNoPutPatchOrDeleteMapping() {
        Class<?> controller = loadApprovalsController();
        List<String> violations = new ArrayList<>();

        for (Method method : controller.getDeclaredMethods()) {
            for (Class<? extends Annotation> ann : MUTATING_NON_POST_ANNOTATIONS) {
                if (method.isAnnotationPresent(ann)) {
                    violations.add(method.getName() + " @" + ann.getSimpleName());
                }
            }
            if (method.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping rm = method.getAnnotation(RequestMapping.class);
                for (var rmMethod : rm.method()) {
                    switch (rmMethod) {
                        case PUT, PATCH, DELETE -> violations.add(
                                method.getName() + " @RequestMapping(method=" + rmMethod + ")");
                        default -> { /* allowed */ }
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("""
                    ApprovalsController must not expose PUT/PATCH/DELETE handlers.
                    Approval rows are append-only post-creation; mutation must go through
                    POST /{id}/resolve or POST /bulk-resolve. Found %d violation(s):
                      - %s""".formatted(violations.size(), String.join("\n  - ", violations)));
        }
    }

    @Test
    void approvalsController_postHandlers_limitedToAllowedPaths() {
        Class<?> controller = loadApprovalsController();
        Set<String> livePostPaths = new TreeSet<>();

        for (Method method : controller.getDeclaredMethods()) {
            PostMapping pm = method.getAnnotation(PostMapping.class);
            if (pm != null) {
                if (pm.value().length == 0) {
                    livePostPaths.add("");
                } else {
                    for (String path : pm.value()) {
                        livePostPaths.add(path);
                    }
                }
            }
        }

        List<String> unexpectedPaths = new ArrayList<>();
        for (String live : livePostPaths) {
            if (!ALLOWED_POST_PATHS.contains(live)) {
                unexpectedPaths.add(live);
            }
        }

        if (!unexpectedPaths.isEmpty()) {
            fail("""
                    ApprovalsController introduced new POST handler path(s) outside the
                    allowed mutation surface. If a new write path is genuinely required,
                    update ALLOWED_POST_PATHS in the same PR with a PR-description
                    justification. Found %d unexpected path(s):
                      - %s
                    Allowed: %s""".formatted(unexpectedPaths.size(),
                    String.join("\n  - ", unexpectedPaths),
                    ALLOWED_POST_PATHS));
        }
    }

    /**
     * Symmetric guard: fails if {@link #ALLOWED_POST_PATHS} contains entries that no
     * longer match any live POST handler on the controller. Stale entries silently
     * widen the allowlist; this test forces deletions to ratchet the surface down.
     */
    @Test
    void allowedPostPaths_doesNotContainStaleEntries() {
        Class<?> controller = loadApprovalsController();
        Set<String> livePostPaths = new TreeSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            PostMapping pm = method.getAnnotation(PostMapping.class);
            if (pm != null) {
                for (String path : pm.value()) {
                    livePostPaths.add(path);
                }
            }
        }
        Set<String> stale = new TreeSet<>(ALLOWED_POST_PATHS);
        stale.removeAll(livePostPaths);
        assertTrue(stale.isEmpty(),
                "ALLOWED_POST_PATHS contains entries that no longer match any POST "
                        + "handler on ApprovalsController (method renamed, path changed, or "
                        + "endpoint deleted). Remove:\n  - " + String.join("\n  - ", stale));
    }

    /**
     * Sanity check that the controller actually has the expected mutation surface today.
     * If this fails after a legitimate endpoint addition, update {@link #ALLOWED_POST_PATHS}
     * AND this expected count in the same PR — both signals matter.
     */
    @Test
    void approvalsController_currentMutationSurface_isExactlyThreePostEndpoints() {
        Class<?> controller = loadApprovalsController();
        int postCount = 0;
        for (Method method : controller.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class)) {
                postCount++;
            }
        }
        assertEquals(3, postCount,
                "Expected exactly 3 POST handlers on ApprovalsController "
                        + "(POST /{id}/resolve, POST /bulk-resolve, POST /{id}/decide). "
                        + "Found " + postCount
                        + ". If you intentionally added another write endpoint, update "
                        + "ALLOWED_POST_PATHS and this expected count in the same PR.");
    }

    /**
     * Forward guard against introducing read-only methods outside the documented surface.
     * The controller exposes GET /pending (legacy ApprovalDTO queue), GET /human-review
     * (REQ-HR-5 undecided HumanReview triage queue), and GET /{id}; future GET additions
     * (e.g., GET /history, GET /by-agent) should land with explicit test updates rather
     * than slipping in invisibly.
     */
    @Test
    void approvalsController_getHandlers_limitedToPendingAndById() {
        Class<?> controller = loadApprovalsController();
        Set<String> expectedGetPaths = Set.of("/pending", "/human-review", "/{id}");
        Set<String> liveGetPaths = new TreeSet<>();

        for (Method method : controller.getDeclaredMethods()) {
            GetMapping gm = method.getAnnotation(GetMapping.class);
            if (gm != null) {
                for (String path : gm.value()) {
                    liveGetPaths.add(path);
                }
            }
        }

        assertEquals(expectedGetPaths, liveGetPaths,
                "ApprovalsController GET surface drifted. Expected " + expectedGetPaths
                        + " but found " + liveGetPaths + ". Update expectedGetPaths and document "
                        + "the new read endpoint in the same PR.");
    }
}
