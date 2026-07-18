package ai.operativus.agentmanager.core.spi;

import ai.operativus.agentmanager.core.entity.WorkflowStep;

/**
 * Domain Responsibility: The per-invocation context passed alongside {@code StepInput} to a
 *     {@link WorkflowNodeExecutor} (DAG plan §2.4). Carries the run-scoped identity / tenancy /
 *     session needed to dispatch downstream work (e.g. an agent run) plus the {@link #node}
 *     itself for its configuration. Kept to core types so the SPI stays in {@code core/} without
 *     pulling in compute/control.
 * State: Stateless (Immutable Record carrier)
 *
 * @param runId        the workflow run id
 * @param workflowId   the parent workflow id
 * @param sessionId    the shared workflow session id (spans every node's agent runs)
 * @param orgId        the run's tenant org id (already resolved; never the security principal)
 * @param userIdentity the principal recorded for audit on dispatched runs
 * @param node         the node (workflow step) being executed — source of its config
 */
public record NodeContext(
        String runId,
        String workflowId,
        String sessionId,
        String orgId,
        String userIdentity,
        WorkflowStep node
) {}
