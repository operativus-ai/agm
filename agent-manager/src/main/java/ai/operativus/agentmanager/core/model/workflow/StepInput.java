package ai.operativus.agentmanager.core.model.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: The immutable input handed to a {@code WorkflowNodeExecutor} for a
 *     single node invocation in the DAG workflow engine (DAG plan §2.2). Replaces the flat
 *     engine's single {@code String currentInput} (limitation L3) with a structured,
 *     addressable payload: a node can read the canonical {@link #input}, any named upstream
 *     output via {@link #previousOutputs}, the ordered direct-predecessor contents via
 *     {@link #upstreamContent}, shared {@link #sessionState}, run-scoped {@link #additionalData},
 *     and (inside a LOOP body) its {@link #iteration}.
 * State: Stateless (Immutable Record carrier)
 *
 * @param runId           the workflow run this invocation belongs to
 * @param nodeId          the node (workflow step) id being executed
 * @param nodeName        human-readable node name (for {@code previousOutputs} addressing)
 * @param input           canonical current input (structured JSON, not just text)
 * @param previousOutputs ALL upstream outputs, addressable by node name (fixes L3)
 * @param upstreamContent ordered direct-predecessor contents (fan-in convenience)
 * @param media           images / files threaded through the graph
 * @param sessionState    shared, mutable workflow session state (read-only view here)
 * @param additionalData  run-scoped constants (initial variables)
 * @param iteration       bound inside LOOP bodies; {@code null} outside a loop (fixes L8)
 */
public record StepInput(
        String runId,
        String nodeId,
        String nodeName,
        JsonNode input,
        Map<String, StepOutput> previousOutputs,
        List<JsonNode> upstreamContent,
        List<MediaRef> media,
        JsonNode sessionState,
        JsonNode additionalData,
        Integer iteration
) {
    public StepInput {
        previousOutputs = previousOutputs == null ? Map.of() : previousOutputs;
        upstreamContent = upstreamContent == null ? List.of() : upstreamContent;
        media = media == null ? List.of() : media;
    }

    /** Returns the {@link #input} as plain text — the textual value, or the JSON string of a non-text node. */
    public String inputText() {
        if (input == null || input.isNull()) return "";
        return input.isTextual() ? input.asText() : input.toString();
    }

    /**
     * Convenience factory for a text-only entry input (graph entry / tests): wraps the string
     * in a {@link TextNode} with empty upstream/media/state collections.
     */
    public static StepInput text(String runId, String nodeId, String nodeName, String input) {
        return new StepInput(runId, nodeId, nodeName, TextNode.valueOf(input == null ? "" : input),
                Map.of(), List.of(), List.of(), null, null, null);
    }
}
