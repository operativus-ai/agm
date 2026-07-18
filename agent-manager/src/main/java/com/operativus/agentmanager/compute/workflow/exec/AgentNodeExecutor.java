package com.operativus.agentmanager.compute.workflow.exec;

import com.operativus.agentmanager.core.model.RunMetrics;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.model.workflow.StepInput;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.spi.NodeContext;
import com.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#AGENT} nodes in the
 *     DAG workflow engine (DAG plan §2.4, DAG-2). Wraps {@link AgentOperations#run} — the same
 *     seam the flat-list engine's {@code executeAgentStep} uses — and maps the {@link RunResponse}
 *     onto a structured {@link StepOutput}: content → text node, {@link RunMetrics} → per-node
 *     token cost + model id, {@code PAUSED} → a bubbled-up HITL pause the scheduler can persist
 *     and resume.
 *
 *     <p>Tenancy mirrors the flat engine exactly: the run executes in the run's resolved org
 *     ({@link NodeContext#orgId}), with the principal recorded as the user identity for audit —
 *     never the security principal as the org (the bug fixed in PR #1134).
 *
 *     <p>Media is threaded through the contract (input → output) but NOT passed into the agent
 *     run here — wiring media into the run goes through the SSRF-guarded media fetch path and is
 *     a later increment; this matches the flat engine, which passes {@code null} media.
 * State: Stateless
 */
@Component
public class AgentNodeExecutor implements WorkflowNodeExecutor {

    private final AgentOperations agentOperations;

    public AgentNodeExecutor(AgentOperations agentOperations) {
        this.agentOperations = agentOperations;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.AGENT;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        RunResponse response = agentOperations.run(
                ctx.node().getAgentId(), in.inputText(), null,
                ctx.sessionId(), ctx.userIdentity(), ctx.orgId(), false, null);
        Instant ended = Instant.now();

        String nodeId = in.nodeId();
        String nodeName = in.nodeName();

        if (response.status() == RunStatus.PAUSED) {
            // The wrapped agent run paused on a HITL gate; bubble it up so the scheduler persists
            // the frontier and re-enters this exact node on resume.
            return StepOutput.paused(nodeId, nodeName, NodeKind.AGENT, "AGENT",
                    com.operativus.agentmanager.core.model.workflow.PauseKind.AGENT, in.media(), started, ended);
        }

        if (response.status() == RunStatus.FAILED) {
            return StepOutput.failure(nodeId, nodeName, NodeKind.AGENT, "AGENT",
                    errorOf(response), in.media(), started, ended);
        }

        return StepOutput.success(nodeId, nodeName, NodeKind.AGENT, "AGENT",
                response.content(), in.media(), started, ended,
                tokenCostOf(response.metrics()), modelOf(response.metrics()));
    }

    private static String errorOf(RunResponse response) {
        RunMetrics m = response.metrics();
        if (m != null && m.errorMessage() != null && !m.errorMessage().isBlank()) {
            return m.errorMessage();
        }
        return "Agent run failed";
    }

    /** Sum of captured input + output tokens; null when the run captured neither (boxed-null contract on {@link RunMetrics}). */
    private static Long tokenCostOf(RunMetrics m) {
        if (m == null) return null;
        Long in = m.inputTokens();
        Long out = m.outputTokens();
        if (in == null && out == null) return null;
        return (in == null ? 0L : in) + (out == null ? 0L : out);
    }

    private static String modelOf(RunMetrics m) {
        return m == null ? null : m.model();
    }
}
