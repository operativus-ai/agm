package ai.operativus.agentmanager.core.spi;

import com.fasterxml.jackson.databind.JsonNode;
import ai.operativus.agentmanager.core.model.workflow.StepInput;

/**
 * Domain Responsibility: Extension SPI for an in-process, deterministic workflow function step
 *     (REQ-DR-5, DAG-6) — the AGM analog of Agno's {@code executor=fn}. A FUNCTION node names a
 *     registered implementation by {@link #key()} (stored in the step's {@code agent_id} column,
 *     mirroring how CONDITION repurposes it for its predicate); {@code FunctionNodeExecutor}
 *     resolves the bean via {@code WorkflowFunctionStepRegistry} and invokes {@link #apply}.
 *     Implementations are plain Spring beans: no LLM, no network expectations — transform the
 *     structured input and return the structured output content. Throwing marks the node failed
 *     (the executor converts the exception to a {@code StepOutput#failure}).
 * State: Stateless (implementations must be stateless Spring beans)
 */
public interface WorkflowFunctionStep {

    /** The registry key a FUNCTION node references (case-insensitive, stored in {@code agent_id}). */
    String key();

    /**
     * Transforms the node's input into its output content.
     *
     * @param in  the structured input (canonical input, addressable upstream outputs, media, state)
     * @param ctx the run/node context (identity, tenancy, session, the node's own config)
     * @return the output content threaded to successor nodes; {@code null} is treated as empty text
     */
    JsonNode apply(StepInput in, NodeContext ctx);
}
