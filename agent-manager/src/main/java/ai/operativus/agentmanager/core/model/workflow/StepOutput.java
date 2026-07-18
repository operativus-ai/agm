package ai.operativus.agentmanager.core.model.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import ai.operativus.agentmanager.core.model.enums.NodeKind;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: The structured result of one node execution in the DAG workflow
 *     engine (DAG plan §2.2). Persisted (one row per node execution) so it serves as the
 *     per-node trace, the fan-in source for downstream JOINs, and the resume state.
 *     Carries control-flow signals the frontier scheduler acts on: {@link #stop} (drain the
 *     whole run), {@link #paused}/{@link #pauseKind} (HITL bubble-up), and {@link #children}
 *     (sub-outputs for PARALLEL/LOOP/CONDITION/ROUTER nesting). Also carries per-node
 *     observability + FinOps ({@link #tokenCost}, {@link #modelId}, timings).
 * State: Stateless (Immutable Record carrier)
 *
 * @param nodeId       the executed node's id
 * @param nodeName     the executed node's name
 * @param kind         the node kind that produced this output
 * @param executorType the executor variant within the kind (e.g. agent vs team; null when n/a)
 * @param content      structured output content (not just a String)
 * @param media        images / files emitted or threaded through
 * @param success      true when the node completed normally
 * @param error        failure detail when {@link #success} is false; null otherwise
 * @param stop         when true, early-terminate the whole run (COMPLETED with this output)
 * @param paused       when true, the node bubbled up a HITL pause; the run is resumable
 * @param pauseKind    the pause flavour (e.g. {@code agent}/{@code tool}/{@code review}/{@code route}); null unless paused
 * @param children     sub-output node ids for nested kinds; empty for leaf nodes
 * @param startedAt    node execution start
 * @param endedAt      node execution end
 * @param tokenCost    input+output tokens captured for this node; null when not captured
 * @param modelId      model that produced the output; null when not applicable
 * @param nested       child resume state when a WORKFLOW node's sub-workflow paused (null otherwise);
 *                     the scheduler records it into the parent {@link DagFrontier#nestedPauses()}
 */
public record StepOutput(
        String nodeId,
        String nodeName,
        NodeKind kind,
        String executorType,
        JsonNode content,
        List<MediaRef> media,
        boolean success,
        String error,
        boolean stop,
        boolean paused,
        String pauseKind,
        List<String> children,
        Instant startedAt,
        Instant endedAt,
        Long tokenCost,
        String modelId,
        List<String> activePorts,
        NestedPause nested
) {
    public StepOutput {
        media = media == null ? List.of() : media;
        children = children == null ? List.of() : children;
        // activePorts left nullable on purpose: null = "no port restriction" (the scheduler
        // activates every outgoing edge), distinct from List.of() = "activate no edges".
    }

    /** Returns the {@link #content} as plain text — the textual value, or the JSON string of a non-text node. */
    public String contentText() {
        if (content == null || content.isNull()) return "";
        return content.isTextual() ? content.asText() : content.toString();
    }

    /** A successful leaf output carrying text content + per-node telemetry. Activates all outgoing edges. */
    public static StepOutput success(String nodeId, String nodeName, NodeKind kind, String executorType,
                                     String content, List<MediaRef> media,
                                     Instant startedAt, Instant endedAt, Long tokenCost, String modelId) {
        return new StepOutput(nodeId, nodeName, kind, executorType,
                TextNode.valueOf(content == null ? "" : content), media,
                true, null, false, false, null, List.of(), startedAt, endedAt, tokenCost, modelId, null, null);
    }

    /** A failed leaf output. */
    public static StepOutput failure(String nodeId, String nodeName, NodeKind kind, String executorType,
                                     String error, List<MediaRef> media, Instant startedAt, Instant endedAt) {
        return new StepOutput(nodeId, nodeName, kind, executorType, TextNode.valueOf(""), media,
                false, error, false, false, null, List.of(), startedAt, endedAt, null, null, null, null);
    }

    /** A HITL pause bubbled up from a node; the scheduler persists the frontier and finalizes the run resumable. */
    public static StepOutput paused(String nodeId, String nodeName, NodeKind kind, String executorType,
                                    String pauseKind, List<MediaRef> media, Instant startedAt, Instant endedAt) {
        return new StepOutput(nodeId, nodeName, kind, executorType, TextNode.valueOf(""), media,
                false, null, false, true, pauseKind, List.of(), startedAt, endedAt, null, null, null, null);
    }

    /**
     * A WORKFLOW node's pause bubbled up from a paused child sub-workflow: carries the child's
     * {@link NestedPause} resume state so the scheduler records it into the parent frontier and the
     * executor's {@code resumeNested} can re-enter the child's exact graph on settle. The child's
     * paused {@code content} is threaded through too — the control layer persists it as the run's
     * {@code currentPayload}, which becomes the operator-settled input on resume (the same
     * carry-the-input contract the ROUTER HITL pause follows).
     */
    public static StepOutput pausedNested(String nodeId, String nodeName, NodeKind kind, String executorType,
                                          String pauseKind, String content, NestedPause nested,
                                          List<MediaRef> media, Instant startedAt, Instant endedAt) {
        return new StepOutput(nodeId, nodeName, kind, executorType,
                TextNode.valueOf(content == null ? "" : content), media,
                false, null, false, true, pauseKind, List.of(), startedAt, endedAt, null, null, null, nested);
    }

    /**
     * A successful routing node (CONDITION / ROUTER) output: threads {@code content} downstream but
     * restricts which outgoing edges the scheduler activates to those whose port label is in
     * {@code activePorts} (e.g. {@code ["true"]} for a CONDITION, a choice key for a ROUTER).
     */
    public static StepOutput branched(String nodeId, String nodeName, NodeKind kind, String executorType,
                                      String content, List<String> activePorts, List<MediaRef> media,
                                      Instant startedAt, Instant endedAt) {
        return new StepOutput(nodeId, nodeName, kind, executorType,
                TextNode.valueOf(content == null ? "" : content), media,
                true, null, false, false, null, List.of(), startedAt, endedAt, null, null, activePorts, null);
    }
}
