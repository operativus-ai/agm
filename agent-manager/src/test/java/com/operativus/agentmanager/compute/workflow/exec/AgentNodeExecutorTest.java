package com.operativus.agentmanager.compute.workflow.exec;

import com.operativus.agentmanager.core.entity.WorkflowStep;
import com.operativus.agentmanager.core.model.RunMetrics;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.model.workflow.MediaRef;
import com.operativus.agentmanager.core.model.workflow.StepInput;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.core.spi.NodeContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link AgentNodeExecutor} (DAG-2): maps {@link RunResponse} → {@link StepOutput},
 * carries token cost + model from {@link RunMetrics}, threads media through, and bubbles up PAUSED /
 * FAILED as control-flow signals. Mirrors the flat engine's tenancy contract (run-org, not principal).
 */
class AgentNodeExecutorTest {

    private final AgentOperations agentOperations = mock(AgentOperations.class);
    private final AgentNodeExecutor executor = new AgentNodeExecutor(agentOperations);

    private static NodeContext ctx() {
        WorkflowStep node = new WorkflowStep("node-1", "wf-1", 1, "agent-7", "AGENT");
        return new NodeContext("run-1", "wf-1", "sess-1", "ORG_A", "alice", node);
    }

    @Test
    void kind_isAgent() {
        assertThat(executor.kind()).isEqualTo(NodeKind.AGENT);
    }

    @Test
    void mapsCompletedRunToSuccessOutput_withTokenCostAndModel() {
        RunMetrics metrics = new RunMetrics(120L, 30L, null, 1, 0, 42L, "gpt-4o", null, null);
        when(agentOperations.run(eq("agent-7"), eq("hello"), isNull(), eq("sess-1"),
                eq("alice"), eq("ORG_A"), eq(false), isNull()))
                .thenReturn(new RunResponse("r-1", "sess-1", "the answer",
                        Map.of(), List.of(), List.of(), RunStatus.COMPLETED, metrics));

        StepInput in = StepInput.text("run-1", "node-1", "summarize", "hello");
        StepOutput out = executor.execute(in, ctx());

        assertThat(out.success()).isTrue();
        assertThat(out.paused()).isFalse();
        assertThat(out.kind()).isEqualTo(NodeKind.AGENT);
        assertThat(out.nodeId()).isEqualTo("node-1");
        assertThat(out.nodeName()).isEqualTo("summarize");
        assertThat(out.contentText()).isEqualTo("the answer");
        assertThat(out.tokenCost()).isEqualTo(150L); // 120 input + 30 output
        assertThat(out.modelId()).isEqualTo("gpt-4o");
        assertThat(out.startedAt()).isNotNull();
        assertThat(out.endedAt()).isNotNull();
    }

    @Test
    void threadsInputMediaThroughToOutput() {
        when(agentOperations.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RunResponse("r-1", "sess-1", "ok",
                        Map.of(), List.of(), List.of(), RunStatus.COMPLETED, null));

        MediaRef img = new MediaRef("image/png", "https://cdn.example.com/a.png");
        StepInput in = new StepInput("run-1", "node-1", "n", null,
                Map.of(), List.of(), List.of(img), null, null, null);

        StepOutput out = executor.execute(in, ctx());

        assertThat(out.media()).containsExactly(img);
    }

    @Test
    void nullMetrics_yieldNullTokenCostAndModel() {
        when(agentOperations.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RunResponse("r-1", "sess-1", "ok",
                        Map.of(), List.of(), List.of(), RunStatus.COMPLETED, null));

        StepOutput out = executor.execute(StepInput.text("run-1", "node-1", "n", "x"), ctx());

        assertThat(out.success()).isTrue();
        assertThat(out.tokenCost()).isNull();
        assertThat(out.modelId()).isNull();
    }

    @Test
    void pausedRunBubblesUpAsPause() {
        when(agentOperations.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RunResponse("r-1", "sess-1", null,
                        Map.of(), List.of(), List.of(), RunStatus.PAUSED, null));

        StepOutput out = executor.execute(StepInput.text("run-1", "node-1", "n", "x"), ctx());

        assertThat(out.paused()).isTrue();
        assertThat(out.success()).isFalse();
        assertThat(out.pauseKind()).isEqualTo("agent");
    }

    @Test
    void failedRunMapsToFailureOutputWithMessage() {
        RunMetrics metrics = new RunMetrics(null, null, null, 0, 1, 5L, null, "TIMEOUT", "model timed out");
        when(agentOperations.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RunResponse("r-1", "sess-1", null,
                        Map.of(), List.of(), List.of(), RunStatus.FAILED, metrics));

        StepOutput out = executor.execute(StepInput.text("run-1", "node-1", "n", "x"), ctx());

        assertThat(out.success()).isFalse();
        assertThat(out.paused()).isFalse();
        assertThat(out.error()).isEqualTo("model timed out");
    }

    @Test
    void runsInRunOrg_notSecurityPrincipal_andPassesNullMedia() {
        when(agentOperations.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RunResponse("r-1", "sess-1", "ok",
                        Map.of(), List.of(), List.of(), RunStatus.COMPLETED, null));

        executor.execute(StepInput.text("run-1", "node-1", "n", "payload"), ctx());

        ArgumentCaptor<String> org = ArgumentCaptor.forClass(String.class);
        verify(agentOperations).run(eq("agent-7"), eq("payload"), isNull(),
                eq("sess-1"), eq("alice"), org.capture(), eq(false), isNull());
        assertThat(org.getValue()).isEqualTo("ORG_A");
    }
}
