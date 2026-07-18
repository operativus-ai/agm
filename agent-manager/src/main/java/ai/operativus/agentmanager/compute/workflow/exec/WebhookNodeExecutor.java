package ai.operativus.agentmanager.compute.workflow.exec;

import ai.operativus.agentmanager.core.model.enums.NodeKind;
import ai.operativus.agentmanager.core.model.workflow.StepInput;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.core.spi.NodeContext;
import ai.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import ai.operativus.agentmanager.core.spi.WorkflowStepExecutorExtension;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#WEBHOOK} nodes
 *     (REQ-DR-5, DAG-6). Delegates to the first {@link WorkflowStepExecutorExtension} that
 *     {@code supports} the node's executor id — stored in the step's {@code agent_id} column,
 *     exactly as the flat engine's WEBHOOK dispatch reads it. The default extension
 *     ({@code WebhookWorkflowStepExecutor}) matches http(s) URLs and POSTs the payload through
 *     the SSRF guard; deployments can register additional extensions for native logic.
 *
 *     <p>Divergence from the flat path (deliberate): no supporting extension is an explicit
 *     {@link StepOutput#failure}, not the flat engine's warn-and-pass-through — a WEBHOOK node
 *     whose executor cannot be resolved is a configuration error, and the DAG path surfaces it
 *     (mirroring FUNCTION's unknown-key contract). Extension exceptions (SSRF rejection, HTTP
 *     failure) are likewise converted to failures so the scheduler records and routes them.
 * State: Stateless
 */
@Component
public class WebhookNodeExecutor implements WorkflowNodeExecutor {

    private final List<WorkflowStepExecutorExtension> extensions;

    public WebhookNodeExecutor(List<WorkflowStepExecutorExtension> extensions) {
        this.extensions = extensions;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WEBHOOK;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        String executorId = ctx.node().getAgentId(); // WEBHOOK repurposes agent_id for the executor id / URL
        if (executorId == null || executorId.isBlank()) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WEBHOOK, "WEBHOOK",
                    "WEBHOOK node has no executor id (expected a URL or extension key in the step's agent_id field)",
                    in.media(), started, Instant.now());
        }
        WorkflowStepExecutorExtension extension = extensions.stream()
                .filter(ext -> ext.supports(executorId))
                .findFirst()
                .orElse(null);
        if (extension == null) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WEBHOOK, "WEBHOOK",
                    "No WorkflowStepExecutorExtension supports executor id '" + executorId + "'",
                    in.media(), started, Instant.now());
        }
        try {
            String result = extension.executeStep(executorId, ctx.workflowId(), ctx.runId(),
                    in.inputText(), Map.of("stepOrder", ctx.node().getStepOrder()));
            return StepOutput.success(in.nodeId(), in.nodeName(), NodeKind.WEBHOOK, "WEBHOOK",
                    result, in.media(), started, Instant.now(), null, null);
        } catch (Exception e) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.WEBHOOK, "WEBHOOK",
                    "Webhook executor '" + executorId + "' failed: " + e.getMessage(),
                    in.media(), started, Instant.now());
        }
    }
}
