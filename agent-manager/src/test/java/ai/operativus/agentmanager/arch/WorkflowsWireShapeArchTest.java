package ai.operativus.agentmanager.arch;

import ai.operativus.agentmanager.control.controller.WorkflowsController;
import ai.operativus.agentmanager.core.model.WorkflowDTO;
import ai.operativus.agentmanager.core.model.WorkflowRunResponse;
import ai.operativus.agentmanager.core.model.WorkflowStepDTO;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Forward guard against silent wire-shape drift on
 *   {@link WorkflowsController}'s GET handlers and their response DTOs. Locks two
 *   contract surfaces at the type level:
 *   <ol>
 *     <li><b>GET handler return types</b> — each handler's full generic return type
 *         (e.g. {@code ResponseEntity<Page<WorkflowDTO>>}) is pinned. A refactor that
 *         silently swaps the response shape (e.g. {@code Page<…>} → {@code List<…>},
 *         losing pagination metadata; or {@code WorkflowDTO} → a wrapper record,
 *         changing the JSON envelope) fails the build instead of breaking the FE at
 *         runtime.</li>
 *     <li><b>Response-DTO field sets</b> — the record-component name set of each
 *         GET-returned DTO is pinned. A renamed, added, or removed field is a wire-shape
 *         break for the FE and must be reflected here in the same PR.</li>
 *   </ol>
 *
 *   <p>Behavioral coverage of these endpoints lives in {@code WorkflowsRuntimeTest},
 *   which asserts the endpoints work end-to-end against the FE-expected JSON. The
 *   behavioral test does NOT assert the full field set is present — a refactor that
 *   removes an unused-by-the-test-but-used-by-the-FE field would silently slip through.
 *   This test closes that gap.
 *
 *   <p>Companion to {@code ControllerReturnTypeArchTest} (flags schema-erasing types
 *   like {@code Map<String,Object>}) and {@code PaginationContractTest} (asserts the
 *   {@code Page<T>} JSON shape via a representative endpoint). This test adds the
 *   per-controller, per-DTO field pinning that neither sibling provides.
 *
 * State: Stateless. Pure-classpath unit test (no Spring context, no Postgres).
 *   Runs in well under 100ms as part of {@code ./mvnw test}.
 */
public class WorkflowsWireShapeArchTest {

    // ─── GET handler return-type pins ────────────────────────────────────────

    @Test
    void listWorkflowsReturnsResponseEntityOfPageOfWorkflowDto() {
        assertReturnType("listWorkflows",
                ResponseEntity.class, Page.class, WorkflowDTO.class);
    }

    @Test
    void getWorkflowReturnsResponseEntityOfWorkflowDto() {
        assertReturnType("getWorkflow", ResponseEntity.class, WorkflowDTO.class);
    }

    @Test
    void getWorkflowStepsReturnsResponseEntityOfListOfWorkflowStepDto() {
        assertReturnType("getWorkflowSteps",
                ResponseEntity.class, List.class, WorkflowStepDTO.class);
    }

    @Test
    void getWorkflowRunsReturnsResponseEntityOfPageOfWorkflowRunResponse() {
        assertReturnType("getWorkflowRuns",
                ResponseEntity.class, Page.class, WorkflowRunResponse.class);
    }

    // ─── Response-DTO field-set pins ─────────────────────────────────────────

    @Test
    void workflowDtoExposesPinnedFields() {
        assertRecordFields(WorkflowDTO.class,
                Set.of("id", "name", "description", "stepCount", "createdAt", "updatedAt"));
    }

    @Test
    void workflowStepDtoExposesPinnedFields() {
        // REQ-DR-4 PR-1 — routerConfig pinned (non-ROUTER rows carry null).
        // REQ-DR-6 PR-2 — onReject pinned (non-CONDITION rows carry null).
        // REQ-DR-6 PR-3 — elseStepId pinned (only for on_reject=ELSE_BRANCH).
        // REQ-DR-6 PR-4 — requiresConfirmation pinned (CONDITION HITL gate).
        // REQ-HR-1 — humanReview pinned (unified HumanReview config; soft-deprecates
        //            the three fields above during the REQ-HR-3..6 migration window).
        assertRecordFields(WorkflowStepDTO.class,
                Set.of("id", "workflowId", "stepOrder", "agentId", "action",
                        "routerConfig", "onReject", "elseStepId", "requiresConfirmation",
                        "humanReview", "createdAt", "updatedAt"));
    }

    @Test
    void workflowRunResponseExposesPinnedFields() {
        assertRecordFields(WorkflowRunResponse.class,
                Set.of("id", "workflowId", "sessionId", "status", "lastStepOrder",
                        "durationMs", "createdAt", "updatedAt"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * Asserts that the named handler on {@link WorkflowsController} has a generic return
     * type matching the supplied raw-type chain. {@code expectedRawChain} is read
     * outside-in: {@code [ResponseEntity, Page, WorkflowDTO]} asserts
     * {@code ResponseEntity<Page<WorkflowDTO>>}.
     */
    private static void assertReturnType(String methodName, Class<?>... expectedRawChain) {
        Method method = findUniqueMethod(methodName);
        Type actual = method.getGenericReturnType();
        Class<?>[] actualChain = unwrap(actual);
        if (!Arrays.equals(actualChain, expectedRawChain)) {
            fail("Wire-shape drift on WorkflowsController#" + methodName
                    + "\n  expected: " + format(expectedRawChain)
                    + "\n  actual:   " + format(actualChain)
                    + "\n\nThis is a FE-visible JSON envelope change. Reconcile with the FE "
                    + "consumers (or update this pin if the change is intentional).");
        }
    }

    private static Class<?>[] unwrap(Type t) {
        java.util.List<Class<?>> chain = new java.util.ArrayList<>();
        while (true) {
            if (t instanceof ParameterizedType pt) {
                chain.add((Class<?>) pt.getRawType());
                Type[] args = pt.getActualTypeArguments();
                if (args.length != 1) {
                    chain.add(Object.class);
                    return chain.toArray(new Class<?>[0]);
                }
                t = args[0];
            } else if (t instanceof Class<?> c) {
                chain.add(c);
                return chain.toArray(new Class<?>[0]);
            } else {
                chain.add(Object.class);
                return chain.toArray(new Class<?>[0]);
            }
        }
    }

    private static String format(Class<?>[] chain) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.length; i++) {
            sb.append(chain[i].getSimpleName());
            if (i < chain.length - 1) sb.append("<");
        }
        sb.append(">".repeat(chain.length - 1));
        return sb.toString();
    }

    private static Method findUniqueMethod(String name) {
        Method[] matches = Arrays.stream(WorkflowsController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .toArray(Method[]::new);
        if (matches.length == 0) {
            fail("WorkflowsController has no method named `" + name
                    + "` — pin is stale (renamed or deleted).");
        }
        if (matches.length > 1) {
            fail("WorkflowsController has " + matches.length + " methods named `" + name
                    + "` — pin is ambiguous. Add parameter-type discrimination to this test.");
        }
        return matches[0];
    }

    private static void assertRecordFields(Class<?> recordClass, Set<String> expected) {
        if (!recordClass.isRecord()) {
            fail(recordClass.getSimpleName() + " is no longer a record. Wire-shape pin assumes "
                    + "a record's component names are the JSON property names; revisit if the "
                    + "DTO is now a class with custom Jackson annotations.");
        }
        Set<String> actual = Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!actual.equals(expected)) {
            Set<String> added = new TreeSet<>(actual);
            added.removeAll(expected);
            Set<String> removed = new TreeSet<>(expected);
            removed.removeAll(actual);
            fail("Wire-shape drift on " + recordClass.getSimpleName()
                    + "\n  added (not pinned):     " + added
                    + "\n  removed (still pinned): " + removed
                    + "\n\nIf the change is intentional, update the pinned field set in this "
                    + "test in the same PR — and confirm the FE consumer was updated too.");
        }
        // Also pin the count, in case of future field reorderings that don't change names.
        assertEquals(expected.size(), recordClass.getRecordComponents().length,
                "Record component count drifted on " + recordClass.getSimpleName());
    }
}
