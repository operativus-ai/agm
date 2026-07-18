package ai.operativus.agentmanager.integration.support;

import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.RunOptions;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Domain Responsibility: Shared {@code @Primary} stub that replaces the real
 *   {@link AgentOperations} (AgentService) in workflow/team integration tests. Scripts
 *   are drained FIFO across concurrent callers. Only the 8-arg {@code run} overload is
 *   expected by callers; everything else throws so unexpected call sites fail loudly.
 *
 *   <p>Bind via {@link RecordingAgentOperationsConfig} (a {@code @TestConfiguration}
 *   that publishes a {@code @Primary @Bean} of this type).
 *
 *   <p>Extracted from the inlined version originally in
 *   {@code WorkflowsRuntimeTest.RecordingAgentOperations} to share across the workflow
 *   integration test suite — the auto-pause + multi-step failure canaries reuse it.
 *
 * State: Mutable per test. Call {@link #reset()} in {@code @BeforeEach} to clear
 *   recorded calls and queued scripts.
 */
public class RecordingAgentOperations implements AgentOperations {

    public final List<Call> calls = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Supplier<RunResponse>> scripts = new ConcurrentLinkedDeque<>();
    private final AtomicInteger seq = new AtomicInteger();

    public void reset() {
        calls.clear();
        scripts.clear();
        seq.set(0);
    }

    public void scriptResponse(String content) {
        scripts.add(() -> new RunResponse(
                "run-" + seq.incrementAndGet(), "sess-stub", content,
                new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null));
    }

    /**
     * Scripts a response that fires {@code sideEffect} before returning. Used by
     * mid-flight tests (e.g., cancellation, pause, state mutation) that need to
     * commit a state change from inside the workflow VT, between two scripted
     * steps, so the next iteration's poll sees the committed value.
     */
    public void scriptResponseWithSideEffect(String content, Runnable sideEffect) {
        scripts.add(() -> {
            sideEffect.run();
            return new RunResponse(
                    "run-" + seq.incrementAndGet(), "sess-stub", content,
                    new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
        });
    }

    public void scriptThrow(RuntimeException ex) {
        scripts.add(() -> { throw ex; });
    }

    @Override
    public RunResponse run(String agentId, String userInput, String sessionId) {
        return run(agentId, userInput, null, sessionId, "sys", "sys", false, null);
    }

    @Override
    public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                           String userId, String orgId, Boolean generateFollowups, RunOptions options) {
        calls.add(new Call(agentId, userInput, sessionId));
        Supplier<RunResponse> s = scripts.poll();
        if (s == null) {
            return new RunResponse("run-auto-" + seq.incrementAndGet(), sessionId,
                    "auto:" + userInput, new HashMap<>(), new ArrayList<>(),
                    new ArrayList<>(), RunStatus.COMPLETED, null);
        }
        return s.get();
    }

    @Override public Flux<AgentStreamEvent> stream(String a, String u, List<Media> m, String s, String uid, String oid, Boolean g, RunOptions o) { throw unsupported(); }
    @Override public String runInBackground(String a, String u, List<Media> m, String s, String uid, String oid, Boolean g, RunOptions o) { throw unsupported(); }
    @Override public String runInBackground(String a, String u, String s) { throw unsupported(); }
    @Override public String runPlayground(String a, String u, String s) { throw unsupported(); }
    @Override public void cancelRun(String runId) { throw unsupported(); }
    @Override public RunResponse continueRun(String runId, String action) { throw unsupported(); }
    @Override public void loadKnowledge(String agentId) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "RecordingAgentOperations does not stub this AgentOperations method");
    }

    public record Call(String agentId, String input, String sessionId) {}
}
