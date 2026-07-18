package com.operativus.agentmanager.core.callback;

import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.ToolCallDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Domain Responsibility: Manages ThreadLocal storage for request-scoped agent execution context, including tool call traces and human-in-the-loop (HITL) approvals. Fulfills Requirement 3.1.2 (Trace Traceability).
 * State: Stateful (ThreadLocal Context Storage)
 */
public class AgentContextHolder {

    public static final ScopedValue<List<ToolCallDTO>> toolTraces = ScopedValue.newInstance();
    public static final ScopedValue<java.util.Set<String>> approvedTools = ScopedValue.newInstance();
    /** REQ-HR follow-up — set of {@code memberAgentId}s for which the operator has
     *  approved the {@code TEAM_MEMBER_DISPATCH} pause on the current team run.
     *  Bound by {@code AgentService.continueRun} on the team-resume path so
     *  {@code TeamMemberHumanReviewGate} short-circuits when re-iterating past
     *  the gated member. Outside a resume scope this is unbound; the gate
     *  treats unbound as "no approvals seeded". */
    public static final ScopedValue<java.util.Set<String>> approvedTeamMembers = ScopedValue.newInstance();
    public static final ScopedValue<String[]> workflowRunId = ScopedValue.newInstance();

    public static final ScopedValue<String> userId = ScopedValue.newInstance();
    public static final ScopedValue<String> sessionId = ScopedValue.newInstance();
    public static final ScopedValue<String> orgId = ScopedValue.newInstance();
    public static final ScopedValue<String> currentRunId = ScopedValue.newInstance();
    public static final ScopedValue<Integer> orchestrationDepth = ScopedValue.newInstance();
    /** Nested sub-workflow depth (DAG-6 WORKFLOW nodes); 0 = top-level run. Distinct from
     *  {@link #orchestrationDepth} (team-orchestration nesting) so the two caps never interact. */
    public static final ScopedValue<Integer> workflowDepth = ScopedValue.newInstance();
    public static final ScopedValue<String> ephemeralContextId = ScopedValue.newInstance();
    public static final ScopedValue<List<String>> allowedKnowledgeBaseIds = ScopedValue.newInstance();
    public static final ScopedValue<Boolean> requiresEncryption = ScopedValue.newInstance();
    /** Per-run gate for the high-volume <b>granular</b> tier of {@code AgentRunEvent}s (per-LLM-call,
     *  per-tool-call, delegation/orchestrator/task traces). Bound once at run start by
     *  {@code AgentService.run} from {@code agm.events.granular-streaming.enabled}; read by
     *  {@code AgentRunEventBus.publish} to short-circuit granular events. When unbound (events emitted
     *  outside a run scope — streaming, workflow, system), the bus falls back to the global default.
     *  The lifecycle tier ({@link com.operativus.agentmanager.core.event.AgentRunEventType#isAlwaysEmitted()})
     *  is never gated by this flag. */
    public static final ScopedValue<Boolean> emitGranularEvents = ScopedValue.newInstance();
    public static final ScopedValue<String> agentId = ScopedValue.newInstance();
    /** Display name of the agent bound for the current run. Bound alongside {@link #agentId}
     *  by {@code AgentService}/{@code AgentStreamManager}/{@code TeamOrchestrationEngine} so
     *  run-timeline events and structured logs can show "Research Assistant" instead of a bare
     *  UUID. Null when unbound — callers fall back to the id. */
    public static final ScopedValue<String> agentName = ScopedValue.newInstance();
    /** Root agent id of the team currently being orchestrated. Bound by
     *  {@code TeamOrchestrationEngine.executeSync}/{@code executeStream} so tools
     *  invoked by team members ({@code DelegationTool}, {@code HandOffTool}) can
     *  feed it to {@code TransitionValidator} for DAG checks against
     *  {@code team_transition_edges}. Null outside a team scope, in which case
     *  validators silently skip DAG validation. */
    public static final ScopedValue<String> teamRootId = ScopedValue.newInstance();
    public static final ScopedValue<RunTelemetryAccumulator> telemetry = ScopedValue.newInstance();

    /** When bound (only by {@code AgentService.runInBackground} today), the inner sync
     *  {@code AgentService.run(...)} reuses this id for the {@code agent_runs} row instead
     *  of generating a fresh UUID and inserting a duplicate row. Without it, every
     *  background call writes TWO rows: the API-tracked one (which never leaves RUNNING)
     *  and the inner-execution one (which gets finalized COMPLETED). Pinned by
     *  {@code BackgroundRunsRuntimeTest}. */
    public static final ScopedValue<String> preAllocatedRunId = ScopedValue.newInstance();

    public static void setWorkflowRunId(String id) {
        if (workflowRunId.isBound()) workflowRunId.get()[0] = id;
    }

    public static String getWorkflowRunId() {
        return workflowRunId.isBound() ? workflowRunId.get()[0] : null;
    }

    public static String getUserId() {
        if (userId.isBound()) return userId.get();
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !SecurityPrincipals.ANONYMOUS_USER.equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return SecurityPrincipals.SYSTEM_PRINCIPAL;
    }

    public static String getSessionId() {
        return sessionId.isBound() ? sessionId.get() : null;
    }

    public static String getOrgId() {
        if (orgId.isBound()) return orgId.get();
        // Fallback for non-request threads (background workers, schedulers): TenantContextFilter
        // doesn't run, so resolve from restored SecurityContext via UserDetailsImpl. Mirrors
        // getUserId() above and TenantContextFilter step 3.
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof com.operativus.agentmanager.control.security.UserDetailsImpl ud) {
            return ud.getOrgId();
        }
        return null;
    }

    public static String getAgentId() {
        return agentId.isBound() ? agentId.get() : null;
    }

    public static String getAgentName() {
        return agentName.isBound() ? agentName.get() : null;
    }

    public static String getTeamRootId() {
        return teamRootId.isBound() ? teamRootId.get() : null;
    }

    public static RunTelemetryAccumulator getTelemetry() {
        return telemetry.isBound() ? telemetry.get() : null;
    }

    public static String getCurrentRunId() {
        return currentRunId.isBound() ? currentRunId.get() : null;
    }

    public static Integer getOrchestrationDepth() {
        return orchestrationDepth.isBound() ? orchestrationDepth.get() : 0;
    }

    public static Integer getWorkflowDepth() {
        return workflowDepth.isBound() ? workflowDepth.get() : 0;
    }

    public static String getEphemeralContextId() {
        return ephemeralContextId.isBound() ? ephemeralContextId.get() : null;
    }

    public static List<String> getAllowedKnowledgeBaseIds() {
        return allowedKnowledgeBaseIds.isBound() ? allowedKnowledgeBaseIds.get() : Collections.emptyList();
    }

    public static boolean getRequiresEncryption() {
        return requiresEncryption.isBound() && Boolean.TRUE.equals(requiresEncryption.get());
    }

    /**
     * @return the per-run granular-event-streaming decision when bound, else {@code null} so the
     *     caller (the event bus) can fall back to the configured global default. Tri-state on purpose:
     *     {@code null} (unbound) is distinct from an explicit {@code false}.
     */
    public static Boolean getEmitGranularEvents() {
        return emitGranularEvents.isBound() ? emitGranularEvents.get() : null;
    }

    public static void addToolCall(ToolCallDTO trace) {
        if (toolTraces.isBound()) toolTraces.get().add(trace);
    }

    public static List<ToolCallDTO> getTraces() {
        return toolTraces.isBound() ? Collections.unmodifiableList(new ArrayList<>(toolTraces.get())) : Collections.emptyList();
    }

    public static void clear() {
        if (toolTraces.isBound()) toolTraces.get().clear();
        if (approvedTools.isBound()) approvedTools.get().clear();
        if (workflowRunId.isBound()) workflowRunId.get()[0] = null;
        if (allowedKnowledgeBaseIds.isBound()) allowedKnowledgeBaseIds.get().clear();
    }
    
    public static void approveTool(String toolName) {
        if (approvedTools.isBound()) approvedTools.get().add(toolName);
    }
    
    public static boolean isToolApproved(String toolName) {
        return approvedTools.isBound() && approvedTools.get().contains(toolName);
    }
    
    public static java.util.Map<String, Object> getSnapshot() {
        java.util.Map<String, Object> snapshot = new java.util.HashMap<>();
        if (toolTraces.isBound()) snapshot.put("toolTraces", new ArrayList<>(toolTraces.get()));
        if (approvedTools.isBound()) snapshot.put("approvedTools", new java.util.HashSet<>(approvedTools.get()));
        if (workflowRunId.isBound()) snapshot.put("workflowRunId", workflowRunId.get()[0]);
        if (allowedKnowledgeBaseIds.isBound()) snapshot.put("allowedKnowledgeBaseIds", new ArrayList<>(allowedKnowledgeBaseIds.get()));
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    public static void setSnapshot(java.util.Map<String, Object> snapshot) {
        clear();
        if (snapshot == null) return;
        
        if (snapshot.containsKey("toolTraces") && toolTraces.isBound()) {
            toolTraces.get().addAll((List<ToolCallDTO>) snapshot.get("toolTraces"));
        }
        if (snapshot.containsKey("approvedTools") && approvedTools.isBound()) {
            approvedTools.get().addAll((java.util.Set<String>) snapshot.get("approvedTools"));
        }
        if (snapshot.containsKey("workflowRunId") && workflowRunId.isBound()) {
            workflowRunId.get()[0] = (String) snapshot.get("workflowRunId");
        }
        if (snapshot.containsKey("allowedKnowledgeBaseIds") && allowedKnowledgeBaseIds.isBound()) {
            allowedKnowledgeBaseIds.get().addAll((List<String>) snapshot.get("allowedKnowledgeBaseIds"));
        }
    }

    /**
     * @summary Bridges all bound ScopedValues into SLF4J MDC for log correlation on the current thread.
     * @logic Called within ContextSnapshotFactory-wrapped background tasks and forked Virtual Threads
     *        to ensure MDC is populated after context propagation. Uses static MDC.put to avoid
     *        dependency on AgentMdcFilter from the core module.
     */
    public static void populateMdcFromScopedValues() {
        String rid = getCurrentRunId();
        if (rid != null) org.slf4j.MDC.put("runId", rid);

        String sid = getSessionId();
        if (sid != null) org.slf4j.MDC.put("sessionId", sid);

        String uid = getUserId();
        if (uid != null && !SecurityPrincipals.SYSTEM_PRINCIPAL.equals(uid)) org.slf4j.MDC.put("userId", uid);

        String oid = getOrgId();
        if (oid != null) org.slf4j.MDC.put("orgId", oid);

        Integer depth = getOrchestrationDepth();
        if (depth != null && depth > 0) org.slf4j.MDC.put("orchestrationDepth", String.valueOf(depth));

        String aid = getAgentId();
        if (aid != null) org.slf4j.MDC.put("agentId", aid);

        String aname = getAgentName();
        if (aname != null) org.slf4j.MDC.put("agentName", aname);
    }

    /**
     * @summary Removes only the MDC keys that {@link #populateMdcFromScopedValues()} owns.
     * @logic Prefer this over MDC.clear() so unrelated keys (e.g. AgentMdcFilter web-filter keys) survive the scope exit.
     */
    public static void clearMdcFromScopedValues() {
        org.slf4j.MDC.remove("runId");
        org.slf4j.MDC.remove("sessionId");
        org.slf4j.MDC.remove("userId");
        org.slf4j.MDC.remove("orgId");
        org.slf4j.MDC.remove("orchestrationDepth");
        org.slf4j.MDC.remove("agentId");
        org.slf4j.MDC.remove("agentName");
    }
}
