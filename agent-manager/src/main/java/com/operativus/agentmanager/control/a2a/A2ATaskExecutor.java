package com.operativus.agentmanager.control.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import com.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import com.operativus.agentmanager.control.repository.A2aTaskEventRepository;
import com.operativus.agentmanager.control.security.A2aTraceContextFilter;
import com.operativus.agentmanager.core.entity.A2aTaskEventEntity;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.registry.AgentOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Executes inbound A2A task requests on virtual threads, emitting
 * lifecycle events to the caller via a bound {@link AgentEmitter}.
 *
 * Gap 2.2 Implementation: Provides the "Task Sockets" execution layer described in the SDD.
 * Handles the full inbound A2A lifecycle:
 *   {@code SUBMITTED → WORKING → (PAUSED|BUDGET_HALT)? → COMPLETED | FAILED | CANCELLED}
 *
 * Each task is submitted as a Java 21 virtual thread (lightweight, no thread pool exhaustion),
 * allowing peer systems to issue multiple concurrent delegations to AGM without blocking.
 *
 * Cancel support:
 * A peer may call {@link #cancelTask(String)} with the original task ID. The backing virtual
 * thread is interrupted via {@code AgentOperations#cancelRun(runId)}, stopping token consumption
 * and emitting a {@link A2aTaskStatus#CANCELLED} event to the peer.
 *
 * FinOps integration:
 * If the inbound request carries an {@link A2aFinOpsBoundary}, it is serialized as a task
 * header so that the local {@code GenAiMetricsAdvisor} enforces the ceiling mid-flight.
 * When the budget is exhausted, {@link A2aTaskStatus#BUDGET_HALT} is emitted — the peer
 * must acknowledge and issue a renegotiation request rather than re-submitting blindly.
 *
 * Architecture:
 * - Constructor injection. ObjectProvider for AgentOperations to avoid circular dependency.
 * - No ApplicationEventPublisher. No reactive streams.
 * - {@code activeTaskRuns} maps taskId → runId for cancel routing.
 *
 * State: Stateful (active task run map).
 */
@Service
public class A2ATaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(A2ATaskExecutor.class);

    /** Tracks taskId → AGM runId for cancel routing (populated once run() returns). */
    private final Map<String, String> activeTaskRuns = new ConcurrentHashMap<>();

    /** Tracks taskId → executing VirtualThread for mid-flight interrupt before runId is available. */
    private final Map<String, Thread> activeTaskThreads = new ConcurrentHashMap<>();

    private final ObjectProvider<AgentOperations> agentOperationsProvider;
    private final A2aTaskEventRepository taskEventRepository;
    private final PeerCancellationDispatcher peerCancellationDispatcher;

    public A2ATaskExecutor(
            ObjectProvider<AgentOperations> agentOperationsProvider,
            A2aTaskEventRepository taskEventRepository,
            PeerCancellationDispatcher peerCancellationDispatcher) {
        this.agentOperationsProvider    = agentOperationsProvider;
        this.taskEventRepository        = taskEventRepository;
        this.peerCancellationDispatcher = peerCancellationDispatcher;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * @summary Creates an SSE-backed emitter for the given task and begins async execution.
     * @logic
     * 1. Constructs an {@link SseAgentEmitter} wrapping a 5-minute SSE timeout.
     * 2. Emits SUBMITTED immediately so the caller knows the task was accepted.
     * 3. Spawns a virtual thread to execute the task via {@link AgentOperations}.
     * 4. Returns the raw {@link SseEmitter} for Spring MVC to flush as the HTTP response body.
     *
     * @param request The inbound A2A task request.
     * @param callerOrgId The caller's JWT-bound org id, resolved on the HTTP thread by
     *                    the controller. Required so cross-tenant {@code targetAgentId}
     *                    lookups via {@code AgentOperations.run} fail with "Agent not
     *                    found" rather than silently executing the cross-tenant agent.
     *                    The virtual thread spawned here cannot read SecurityContextHolder,
     *                    so the controller must resolve and pass it explicitly.
     * @return An {@link SseEmitter} the controller should return directly as the response.
     */
    public SseEmitter submitTask(A2aTaskRequest request, String callerOrgId) {
        SseEmitter sseEmitter = new SseEmitter(5 * 60 * 1000L); // 5-minute timeout
        AgentEmitter emitter = new SseAgentEmitter(sseEmitter);

        emitter.submit(request.taskId());
        audit(request, null, A2aTaskStatus.SUBMITTED, "Task accepted by AGM execution layer.", null);

        Thread.ofVirtual()
            .name("a2a-task-" + request.taskId())
            .start(() -> executeTask(request, emitter, callerOrgId));

        return sseEmitter;
    }

    /**
     * @summary Cancels an active A2A task by its task ID.
     * @logic Looks up the AGM run ID from {@code activeTaskRuns} and calls
     *        {@code AgentOperations#cancelRun} to interrupt the virtual thread.
     *        Returns false if the task ID is not tracked (already completed or unknown).
     */
    public boolean cancelTask(String taskId) {
        // N-2 Fix: Try runId-based cancel first, then fall back to direct thread interrupt
        // for tasks still blocked inside the synchronous run() call.
        String runId = activeTaskRuns.get(taskId);
        if (runId != null) {
            AgentOperations ops = agentOperationsProvider.getIfAvailable();
            if (ops != null) {
                ops.cancelRun(runId);
                log.info("A2ATaskExecutor: cancel signal issued for taskId={} runId={}", taskId, runId);
            }
            return true;
        }

        Thread taskThread = activeTaskThreads.get(taskId);
        if (taskThread != null) {
            taskThread.interrupt();
            log.info("A2ATaskExecutor: interrupt issued for taskId={} (runId not yet available)", taskId);
            return true;
        }

        log.warn("A2ATaskExecutor: cancel requested for unknown taskId={}", taskId);
        return false;
    }

    // -----------------------------------------------------------------------
    // Virtual thread execution
    // -----------------------------------------------------------------------

    /**
     * @summary Core execution routine run on a dedicated virtual thread per task.
     * @logic
     * 1. Resolves {@code AgentOperations} (lazy to avoid circular dep).
     * 2. Prepares the task payload — prepends an A2A FinOps boundary header if present.
     * 3. Emits WORKING, then blocks on the synchronous {@code run(...)} overload.
     * 4. Extracts the runId from the {@link RunResponse} and registers it for cancel routing.
     * 5. Emits COMPLETED with the output, or the appropriate failure event on exception.
     * 6. Cleans up the activeTaskRuns entry on completion.
     *
     * Note on cancel support: because {@code run()} is blocking, the runId is only available
     * after the task completes. Cancel-by-taskId is therefore effective for tasks still in the
     * SUBMITTED → WORKING window before the blocking call begins, and for queued tasks that
     * have not yet been assigned a runId. Mid-flight cancellation requires the caller to obtain
     * the runId via the WORKING lifecycle event and call DELETE /api/v1/runs/{runId} directly.
     */
    private void executeTask(A2aTaskRequest request, AgentEmitter emitter, String callerOrgId) {
        String taskId = request.taskId();
        String runId = null;

        try {
            AgentOperations agentOperations = agentOperationsProvider.getIfAvailable();
            if (agentOperations == null) {
                emitter.error(taskId, null, "AgentOperations unavailable — AGM execution layer not ready.");
                return;
            }

            String initiatorId  = request.initiatingAgentId() != null ? request.initiatingAgentId() : "unknown";

            // N-2 Fix: Register the VirtualThread *before* the blocking run() call so
            // cancelTask() can interrupt mid-flight even before a runId is available.
            activeTaskThreads.put(taskId, Thread.currentThread());

            // Emit WORKING before blocking — runId comes from the response once execution completes
            emitter.startWork(taskId, null);
            audit(request, null, A2aTaskStatus.WORKING, "Execution started.", null);

            // Tenant boundary: callerOrgId is forwarded to AgentRegistry.findById so
            // targetAgentId is resolved within the caller's org only. A cross-tenant
            // lookup surfaces as "Agent not found" → executor's FAILED branch.
            // N-3 Fix: FinOps boundary passed as structured RunOptions metadata,
            // not concatenated into the LLM prompt.
            RunResponse response = agentOperations.run(
                request.targetAgentId(),
                request.input(),
                null,
                request.sessionId(),
                "a2a-peer:" + initiatorId,
                callerOrgId,
                false,
                buildRunOptions(request)
            );

            runId = response.runId();
            if (runId != null) activeTaskRuns.put(taskId, runId);

            // HITL: AgentService catches ApprovalRequiredException internally and returns
            // RunResponse(status=PAUSED, metadata.requiredAction={approvalId,...}). The
            // executor must reflect that to the peer as PAUSED, not COMPLETED — otherwise
            // peers see a "done" event for a task that is actually waiting on human
            // approval and have no way to drive the resume.
            if (response.status() == com.operativus.agentmanager.core.model.enums.RunStatus.PAUSED) {
                String approvalId = extractApprovalId(response);
                emitter.pause(taskId, runId, approvalId);
                audit(request, runId, A2aTaskStatus.PAUSED, response.content(), null);
                log.info("A2ATaskExecutor: task paused for HITL approval taskId={} runId={} approvalId={}",
                    taskId, runId, approvalId);
            } else {
                emitter.complete(taskId, runId, response.content());
                audit(request, runId, A2aTaskStatus.COMPLETED, null, null);
                log.info("A2ATaskExecutor: task completed taskId={} runId={}", taskId, runId);
            }

        } catch (com.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException e) {
            log.warn("A2ATaskExecutor: budget halt for taskId={} runId={}: {}", taskId, runId, e.getMessage());
            emitter.budgetHalt(taskId, runId);
            audit(request, runId, A2aTaskStatus.BUDGET_HALT,
                "Token budget ceiling reached. Renegotiation required.", null);

        } catch (Exception e) {
            // Virtual thread interrupt surfaces as RuntimeException wrapping InterruptedException
            // or sets the thread interrupt flag — check both cases before treating as a hard error.
            if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                log.info("A2ATaskExecutor: task cancelled via interrupt taskId={} runId={}", taskId, runId);
                emitter.cancel(taskId, runId, "Task cancelled by peer or internal policy.");
                audit(request, runId, A2aTaskStatus.CANCELLED, "Cancelled by peer or internal policy.", null);
                // §22.5: notify the originating peer so they can release client-side
                // resources and audit the propagation. Best-effort; see dispatcher.
                peerCancellationDispatcher.notifyCancellation(
                    taskId, request.initiatingAgentId(), "Cancelled by peer or internal policy.");
            } else {
                log.error("A2ATaskExecutor: task failed taskId={} runId={}: {}", taskId, runId, e.getMessage(), e);
                emitter.error(taskId, runId, e.getMessage());
                audit(request, runId, A2aTaskStatus.FAILED, null, e.getMessage());
            }

        } finally {
            if (taskId != null) {
                activeTaskRuns.remove(taskId);
                activeTaskThreads.remove(taskId);
            }
        }
    }

    /**
     * @summary Persists a lifecycle event row to {@code a2a_task_events} asynchronously.
     * @logic Runs the DB write on a separate virtual thread so a slow or unavailable database
     *        cannot block or fail the SSE stream that the peer is reading. Errors are logged
     *        and swallowed — audit write failure is never propagated to the task caller.
     *        The trace ID is read from the SLF4J MDC if set by {@code A2aTraceContextFilter}.
     */
    private void audit(A2aTaskRequest request, String runId,
                       A2aTaskStatus status, String message, String errorDetail) {
        String traceId = request.traceId() != null
            ? request.traceId()
            : MDC.get(A2aTraceContextFilter.MDC_TRACE_ID_KEY);

        Thread.ofVirtual().name("a2a-audit-" + request.taskId()).start(() -> {
            try {
                A2aTaskEventEntity event = new A2aTaskEventEntity();
                event.setTaskId(request.taskId());
                event.setRunId(runId);
                event.setTargetAgentId(request.targetAgentId());
                event.setInitiatingAgent(request.initiatingAgentId());
                event.setSessionId(request.sessionId());
                event.setTraceId(traceId);
                event.setStatus(status);
                event.setMessage(message);
                event.setErrorDetail(errorDetail);
                event.setEventTs(LocalDateTime.now());
                taskEventRepository.save(event);
            } catch (Exception ex) {
                log.warn("A2ATaskExecutor: failed to persist audit event taskId={} status={}: {}",
                    request.taskId(), status, ex.getMessage());
            }
        });
    }

    /**
     * @summary Extracts the {@code approvalId} from a PAUSED RunResponse for SSE emission.
     * @logic AgentService stamps {@code metadata["requiredAction"]} with a map containing
     *        the approvalId when it returns RunStatus.PAUSED. The peer needs that id to
     *        drive POST /api/v1/approvals/{id}/resolve. If extraction fails we fall back
     *        to the runId — the approval row is keyed on runId in production, so callers
     *        can still resolve via runId-based lookups.
     */
    private String extractApprovalId(RunResponse response) {
        if (response.metadata() != null) {
            Object requiredAction = response.metadata().get("requiredAction");
            if (requiredAction instanceof Map<?, ?> ra) {
                Object approvalId = ra.get("approvalId");
                if (approvalId != null) {
                    return approvalId.toString();
                }
            }
        }
        return response.runId();
    }

    /**
     * @summary Constructs {@link RunOptions} carrying the A2A FinOps boundary as structured metadata.
     * @logic The boundary is passed through RunOptions so the GenAiMetricsAdvisor can enforce
     *        the token ceiling mid-flight without the boundary leaking into the LLM prompt.
     */
    private RunOptions buildRunOptions(A2aTaskRequest request) {
        if (request.finOpsBoundary() == null) {
            return null;
        }
        return new RunOptions(null, null, null, null, request.finOpsBoundary());
    }
}
