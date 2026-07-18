package ai.operativus.agentmanager.integration.crosscutting;

import ai.operativus.agentmanager.compute.config.AgentMdcFilter;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage for the T053 / matrix §27.5 virtual-threads
 *   and MDC-propagation surface. Pins three independent contracts that together form the
 *   "log-correlation works on Loom" story:
 *   1. {@code spring.threads.virtual.enabled=true} is in effect at runtime and Boot's
 *      {@code applicationTaskExecutor} bean is a virtual-thread-per-task executor (overridden
 *      by {@code AgentManagerConfig#applicationTaskExecutor}).
 *   2. {@link AgentMdcFilter#populateMdc()} and {@link AgentMdcFilter#clearMdc()} round-trip
 *      all agentic MDC keys (runId/sessionId/userId/orgId/orchestrationDepth) from
 *      {@link AgentContextHolder}'s bound {@code ScopedValue}s and back to a clean slate —
 *      critical because virtual-thread carrier reuse will leak stale MDC values if clear
 *      misses a key.
 *   3. {@link ContextSnapshotFactory}{@code .builder().build().captureAll().wrap(...)} —
 *      the wrapper used by {@code RunExecutionManager}, {@code WorkflowService},
 *      and {@code SwarmOrchestrator} to fork per-task virtual
 *      threads — runs the task on a virtual thread AND auto-propagates the parent's SLF4J
 *      MDC snapshot, now that
 *      {@link ai.operativus.agentmanager.compute.config.MdcContextPropagationConfig}
 *      registers {@code Slf4jThreadLocalAccessor} on the global
 *      {@link io.micrometer.context.ContextRegistry}. Case 6 pins this contract so an
 *      accidental deregistration (or accessor-order bug) breaks the build.
 * State: Stateless. Every test calls {@link #clearMdcAfterEach} to prevent carrier-thread
 *   MDC leaks across tests in this JVM.
 *
 * Scope: the whole point of these tests is to exercise the production wiring without any
 *   test-only shim that would mask the real Loom + MDC interaction. We use the application's
 *   own {@code applicationTaskExecutor} bean (autowired as {@code AsyncTaskExecutor}) for
 *   case 2 instead of a locally instantiated executor, so a future rename or accidental
 *   downgrade to platform threads breaks this test loudly.
 *
 * Matrix gaps marked {@code @Disabled}: verifying the HTTP request handler thread is virtual
 *   requires a production controller to report {@code Thread.currentThread().isVirtual()};
 *   we don't want to add a test-only endpoint. Verifying MDC cleanup after a {@code @Async}
 *   boundary requires an {@code @Async}-annotated bean in the product, which does not exist
 *   today ({@code Thread.ofVirtual().start} and {@code VirtualThreadPerTaskExecutor.submit}
 *   are used directly instead).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §27.5.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class VirtualThreadMdcRuntimeTest extends BaseIntegrationTest {

    @Autowired private Environment environment;
    @Autowired private AsyncTaskExecutor applicationTaskExecutor;

    @AfterEach
    void clearMdcAfterEach() {
        // Belt-and-braces: virtual-thread carriers are pooled across the JVM, so a test that
        // sets MDC and fails before cleanup would leak stale values into later tests.
        AgentMdcFilter.clearMdc();
        MDC.clear();
    }

    // §27.5 case 1 — the virtual-thread property is resolvable at runtime. If this flips to
    // false (via a bad profile include or env override), Boot silently falls back to platform
    // threads and the rest of this file's assertions stop reflecting production behaviour.
    @Test
    void virtualThreadsPropertyIsEnabledInRuntimeEnvironment() {
        String value = environment.getProperty("spring.threads.virtual.enabled");
        assertEquals("true", value,
                "spring.threads.virtual.enabled must be 'true' at runtime; falling back to "
                        + "platform threads would break context propagation assumptions across "
                        + "RunExecutionManager, WorkflowService, SwarmOrchestrator");
    }

    // §27.5 case 2 — Spring's applicationTaskExecutor bean actually dispatches tasks onto
    // virtual threads. This is overridden in AgentManagerConfig#applicationTaskExecutor, so
    // this test breaks if either (a) the @Bean override is removed and Boot's default kicks
    // in with a mis-configured environment, or (b) the override is edited to use platform
    // threads. The ContextPropagatingTaskDecorator on the adapter makes it a fair proxy for
    // Boot's @Async dispatch path as well.
    @Test
    void applicationTaskExecutorSchedulesTasksOnVirtualThreads() throws Exception {
        AtomicBoolean wasVirtual = new AtomicBoolean(false);
        AtomicReference<String> threadName = new AtomicReference<>();

        applicationTaskExecutor.submit(() -> {
            wasVirtual.set(Thread.currentThread().isVirtual());
            threadName.set(Thread.currentThread().toString());
        }).get();

        assertTrue(wasVirtual.get(),
                "applicationTaskExecutor must run tasks on a virtual thread; observed: " + threadName.get());
    }

    // §27.5 case 3 — AgentMdcFilter.populateMdc is the bridge between ScopedValue-based
    // request context and SLF4J's ThreadLocal MDC. Bound ScopedValues must surface as MDC
    // keys under the documented names. Cases 4 + 5 verify the inverse (clear) and the
    // AgentContextHolder-side helper.
    @Test
    void agentMdcFilterPopulatesMdcFromBoundScopedValues() {
        ScopedValue.where(AgentContextHolder.currentRunId, "run-t053")
                .where(AgentContextHolder.sessionId, "session-t053")
                .where(AgentContextHolder.userId, "user-t053")
                .where(AgentContextHolder.orgId, "org-t053")
                .where(AgentContextHolder.orchestrationDepth, 2)
                .run(() -> {
                    AgentMdcFilter.populateMdc();

                    assertEquals("run-t053", MDC.get(AgentMdcFilter.MDC_RUN_ID));
                    assertEquals("session-t053", MDC.get(AgentMdcFilter.MDC_SESSION_ID));
                    assertEquals("user-t053", MDC.get(AgentMdcFilter.MDC_USER_ID));
                    assertEquals("org-t053", MDC.get(AgentMdcFilter.MDC_ORG_ID));
                    assertEquals("2", MDC.get(AgentMdcFilter.MDC_ORCHESTRATION_DEPTH));
                });
    }

    // §27.5 case 4 — clearMdc scrubs every agentic key, including keys that populateMdc
    // would have set from a previous request on the same carrier thread. This is the
    // virtual-thread correctness guarantee: without it, a follow-up task on the same
    // carrier would log with the previous request's runId/userId, which is both a
    // correlation bug and a privacy bug.
    @Test
    void agentMdcFilterClearMdcRemovesAllAgenticKeys() {
        MDC.put(AgentMdcFilter.MDC_RUN_ID, "leftover-run");
        MDC.put(AgentMdcFilter.MDC_SESSION_ID, "leftover-session");
        MDC.put(AgentMdcFilter.MDC_AGENT_ID, "leftover-agent");
        MDC.put(AgentMdcFilter.MDC_USER_ID, "leftover-user");
        MDC.put(AgentMdcFilter.MDC_ORG_ID, "leftover-org");
        MDC.put(AgentMdcFilter.MDC_PHASE, "leftover-phase");
        MDC.put(AgentMdcFilter.MDC_ORCHESTRATION_DEPTH, "7");

        AgentMdcFilter.clearMdc();

        assertNull(MDC.get(AgentMdcFilter.MDC_RUN_ID), "runId must be cleared");
        assertNull(MDC.get(AgentMdcFilter.MDC_SESSION_ID), "sessionId must be cleared");
        assertNull(MDC.get(AgentMdcFilter.MDC_AGENT_ID), "agentId must be cleared");
        assertNull(MDC.get(AgentMdcFilter.MDC_USER_ID), "userId must be cleared");
        assertNull(MDC.get(AgentMdcFilter.MDC_ORG_ID), "orgId must be cleared");
        assertNull(MDC.get(AgentMdcFilter.MDC_PHASE), "phase must be cleared");
        assertNull(MDC.get(AgentMdcFilter.MDC_ORCHESTRATION_DEPTH), "orchestrationDepth must be cleared");
    }

    // §27.5 case 5 — AgentContextHolder exposes its own populate helper that mirrors the
    // filter's behaviour. Required because background tasks dispatched via the snapshot
    // factory don't go through the HTTP filter but still need MDC populated after the
    // snapshot restores ScopedValues on the fresh virtual thread.
    @Test
    void agentContextHolderPopulateMdcFromScopedValuesMatchesFilterKeys() {
        ScopedValue.where(AgentContextHolder.currentRunId, "sv-run")
                .where(AgentContextHolder.sessionId, "sv-session")
                .where(AgentContextHolder.orgId, "sv-org")
                .where(AgentContextHolder.orchestrationDepth, 3)
                .run(() -> {
                    AgentContextHolder.populateMdcFromScopedValues();

                    assertEquals("sv-run", MDC.get("runId"));
                    assertEquals("sv-session", MDC.get("sessionId"));
                    assertEquals("sv-org", MDC.get("orgId"));
                    assertEquals("3", MDC.get("orchestrationDepth"));
                });
    }

    // §27.5 case 6 — pins the MDC auto-propagation contract.
    // {@link ai.operativus.agentmanager.compute.config.MdcContextPropagationConfig} registers
    // {@link io.micrometer.context.integration.Slf4jThreadLocalAccessor} on the global
    // {@link io.micrometer.context.ContextRegistry}. As a result,
    // {@code ContextSnapshotFactory.builder().build().captureAll()} captures the parent
    // thread's MDC snapshot at wrap time and restores it on the forked virtual thread before
    // the task runs. A clean slate (null MDC inside the task) would mean the accessor
    // registration regressed — log correlation in background orchestrators would break
    // silently, and the production {@code populateMdcFromScopedValues()} bridges would be
    // carrying all the weight of correlation on their own.
    @Test
    void contextSnapshotWrapAutoPropagatesMdcOntoVirtualThread() throws Exception {
        MDC.put(AgentMdcFilter.MDC_RUN_ID, "parent-run");
        MDC.put(AgentMdcFilter.MDC_USER_ID, "parent-user");

        AtomicReference<String> observedRunId = new AtomicReference<>();
        AtomicReference<String> observedUserId = new AtomicReference<>();
        AtomicBoolean observedVirtual = new AtomicBoolean(false);

        Runnable wrapped = ContextSnapshotFactory.builder().build().captureAll().wrap(() -> {
            observedRunId.set(MDC.get(AgentMdcFilter.MDC_RUN_ID));
            observedUserId.set(MDC.get(AgentMdcFilter.MDC_USER_ID));
            observedVirtual.set(Thread.currentThread().isVirtual());
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(wrapped).get();
        }

        assertTrue(observedVirtual.get(),
                "newVirtualThreadPerTaskExecutor must schedule the task onto a virtual thread");
        assertEquals("parent-run", observedRunId.get(),
                "runId must be propagated onto the fresh virtual thread via Slf4jThreadLocalAccessor. "
                        + "A null here means MdcContextPropagationConfig did not register the accessor "
                        + "(or registration fired after the snapshot was captured).");
        assertEquals("parent-user", observedUserId.get(),
                "userId must be propagated onto the fresh virtual thread — same accessor, same reason");
    }

    // §27.5 case 7 — Production /api/diagnostics/thread reports back the serving thread's
    // isVirtual() flag, so we can positively assert that @RestController methods run on
    // virtual threads from a real black-box call (through the full Spring Security filter
    // chain, not a reflective shortcut).
    @Test
    void httpRequestHandlerThreadIsVirtualAtControllerEntryPoint() {
        HttpHeaders auth = authenticateAs("vt-probe", "vt-probe@example.com", "Correct-Horse-9!", List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/diagnostics/thread"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body, "diagnostics endpoint must return a body");
        assertEquals(Boolean.TRUE, body.get("virtual"),
                "HTTP handler thread must be virtual; observed thread='" + body.get("name")
                        + "' virtual=" + body.get("virtual"));
    }

    // §27.5 case 8 — MATRIX GAP. Spring's @Async dispatch path also routes through the
    // applicationTaskExecutor and should propagate + clear MDC at the boundary, but no
    // current product bean is @Async-annotated (every background entry point uses
    // Thread.ofVirtual() or Executors.newVirtualThreadPerTaskExecutor() directly). Enable
    // when a product @Async method exists and this class can exercise it end-to-end.
    @Test
    @Disabled("Gap: no @Async-annotated product bean exists today. Enable when a product "
            + "service adopts @Async so this class can verify MDC cleanup at the async boundary.")
    void mdcIsClearedAfterSpringAsyncMethodBoundaryReturns() {
        // Intentionally empty — reinstate with a real @Async bean target.
    }
}
