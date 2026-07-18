package ai.operativus.agentmanager.compute.workflow;

import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.entity.WorkflowStep;
import ai.operativus.agentmanager.core.model.enums.NodeKind;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.model.workflow.StepInput;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.core.spi.NodeContext;
import ai.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import ai.operativus.agentmanager.control.repository.WorkflowNodeRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for per-node retry + timeout in {@link DagWorkflowExecutor#executeNode}. Drives the
 * real scheduler over a single in-degree-0 node with a fake registry that returns a scripted
 * executor, so we control failure/slow behaviour deterministically without a live agent run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DagNodeRetryTimeoutTest {

    @Mock private WorkflowNodeExecutorRegistry registry;
    @Mock private WorkflowNodeRunRepository nodeRunRepository;

    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    private DagWorkflowExecutor newExecutor() {
        when(nodeRunRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // defaults: 1 attempt, no backoff, no timeout (the prior "run once" behaviour)
        return new DagWorkflowExecutor(registry, nodeRunRepository, 4, 1, 0L, 0L);
    }

    private WorkflowRun run() {
        return new WorkflowRun("run-1", "wf-1", "sess-1", RunStatus.RUNNING, 0, null, "ORG-1");
    }

    private WorkflowStep node() {
        return new WorkflowStep("n1", "wf-1", 0, "agent-x", "AGENT");
    }

    /** A node's execute() behaviour — the SAM the test scripts; wrapped into a WorkflowNodeExecutor. */
    @FunctionalInterface
    private interface Behavior {
        StepOutput run(StepInput in, NodeContext ctx);
    }

    private void stub(Behavior behavior) {
        WorkflowNodeExecutor exec = new WorkflowNodeExecutor() {
            @Override public NodeKind kind() { return NodeKind.AGENT; }
            @Override public StepOutput execute(StepInput in, NodeContext ctx) { return behavior.run(in, ctx); }
        };
        when(registry.resolve(any())).thenReturn(Optional.of(exec));
    }

    private static StepOutput fail() {
        return StepOutput.failure("n1", "n1", NodeKind.AGENT, "AGENT", "boom", List.of(), Instant.now(), Instant.now());
    }

    private static StepOutput ok() {
        return StepOutput.success("n1", "n1", NodeKind.AGENT, "AGENT", "ok", List.of(), Instant.now(), Instant.now(), 0L, "m");
    }

    @Test
    void retriesUntilSuccess_withinMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        stub((in, ctx) -> calls.incrementAndGet() <= 2 ? fail() : ok());

        WorkflowStep n = node();
        n.setRetryMaxAttempts(3);
        StepOutput out = newExecutor().execute(run(), List.of(n), List.of(), "input", NOT_CANCELLED);

        assertTrue(out.success(), "node should succeed on the 3rd attempt");
        assertEquals(3, calls.get(), "should have retried twice before the successful 3rd attempt");
    }

    @Test
    void exhaustsAttempts_thenFails() {
        AtomicInteger calls = new AtomicInteger();
        stub((in, ctx) -> { calls.incrementAndGet(); return fail(); });

        WorkflowStep n = node();
        n.setRetryMaxAttempts(2);
        StepOutput out = newExecutor().execute(run(), List.of(n), List.of(), "input", NOT_CANCELLED);

        assertFalse(out.success(), "always-failing node fails after exhausting attempts");
        assertEquals(2, calls.get(), "should attempt exactly retry_max_attempts times");
    }

    @Test
    void defaultIsSingleAttempt_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        stub((in, ctx) -> { calls.incrementAndGet(); return fail(); });

        // no per-node config → global default (1) → zero behaviour change vs. pre-retry engine
        StepOutput out = newExecutor().execute(run(), List.of(node()), List.of(), "input", NOT_CANCELLED);

        assertFalse(out.success());
        assertEquals(1, calls.get(), "with no retry config the node runs exactly once");
    }

    @Test
    void pausedOutput_isNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        stub((in, ctx) -> {
            calls.incrementAndGet();
            return StepOutput.paused("n1", "n1", NodeKind.AGENT, "AGENT", "review", List.of(), Instant.now(), Instant.now());
        });

        WorkflowStep n = node();
        n.setRetryMaxAttempts(3); // even with retries allowed, a HITL pause must bubble straight up
        StepOutput out = newExecutor().execute(run(), List.of(n), List.of(), "input", NOT_CANCELLED);

        assertTrue(out.paused(), "a pause is a terminal outcome, not a failure");
        assertEquals(1, calls.get(), "a paused node must not be retried");
    }

    @Test
    void slowNode_exceedingTimeout_failsWithTimeoutError() {
        stub((in, ctx) -> {
            try { Thread.sleep(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ok();
        });

        WorkflowStep n = node();
        n.setTimeoutMs(100L); // budget far below the 1s the executor takes
        StepOutput out = newExecutor().execute(run(), List.of(n), List.of(), "input", NOT_CANCELLED);

        assertFalse(out.success(), "a node exceeding its timeout is a failure");
        assertTrue(out.error() != null && out.error().contains("timeout"),
                "failure error should explain the timeout, got: " + out.error());
    }
}
