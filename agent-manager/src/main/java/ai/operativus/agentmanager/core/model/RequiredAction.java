package ai.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Domain Responsibility: Typed payload describing why an agent run paused. Persisted as
 *   JSON in {@code agent_runs.required_action} and surfaced verbatim in the {@code RunResponse}
 *   metadata under key {@code "requiredAction"}. Replaces the historical brittle
 *   {@code Map.toString()} format ({@code "{type=TOOL_APPROVAL, tool=delete_database, args={}, approvalId=…}"}),
 *   which broke on commas inside JSON args and depended on JVM-specific Map.toString() behavior.
 *
 *   Three variants exist today (gated by {@link #type()}):
 *   <ul>
 *     <li>{@link RequiredActionType#TOOL_APPROVAL} — populates {@link #tool()},
 *         {@link #args()}, {@link #approvalId()}; resume parser at
 *         {@code AgentService.continueRun} reads {@code tool} to seed the approved-tools
 *         ScopedValue.</li>
 *     <li>{@link RequiredActionType#SWARM_ESCALATION_APPROVAL} — populates
 *         {@link #sourceAgentId()}, {@link #targetAgentId()}, {@link #sourceTier()},
 *         {@link #targetTier()}, {@link #escalationId()}.</li>
 *   </ul>
 *
 *   Common diagnostic fields ({@link #traceId()}, {@link #reasoningLineage()},
 *   {@link #dagContext()}) appear on both variants. Variant-specific fields are
 *   {@code null} when not applicable; {@code @JsonInclude(NON_NULL)} keeps the wire
 *   payload tight.
 *
 *   The {@link #pausedChildRunId()} field is set ONLY by the {@link #teamMemberPause}
 *   factory — non-null indicates that this is a team-level paused run pointing at the
 *   member run that actually holds the approval row. Single-agent runs leave it null
 *   (and {@code @JsonInclude(NON_NULL)} elides it from the wire payload). Used by
 *   {@code AgentService.continueRun} to reject team-level resume attempts and direct
 *   the caller to the child runId. See Tier 2.5 F2/F3 fix.
 *
 * State: Immutable record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequiredAction(
        RequiredActionType type,
        String tool,
        String args,
        String approvalId,
        String sourceAgentId,
        String targetAgentId,
        Integer sourceTier,
        Integer targetTier,
        String escalationId,
        String traceId,
        String reasoningLineage,
        String dagContext,
        String pausedChildRunId
) {

    public static RequiredAction toolApproval(String tool, String args, String approvalId,
                                              String traceId, String reasoningLineage, String dagContext) {
        return new RequiredAction(RequiredActionType.TOOL_APPROVAL,
                tool, args, approvalId,
                null, null, null, null, null,
                traceId, reasoningLineage, dagContext,
                null);
    }

    public static RequiredAction swarmEscalation(String sourceAgentId, String targetAgentId,
                                                 int sourceTier, int targetTier,
                                                 String escalationId,
                                                 String traceId, String reasoningLineage, String dagContext) {
        return new RequiredAction(RequiredActionType.SWARM_ESCALATION_APPROVAL,
                null, null, null,
                sourceAgentId, targetAgentId, sourceTier, targetTier, escalationId,
                traceId, reasoningLineage, dagContext,
                null);
    }

    /**
     * REQ-HR follow-up — team orchestrator paused BEFORE dispatching a member whose
     * {@code team_members.human_review} requires confirmation. Distinct from
     * {@link #teamMemberPause}, which lifts a child member's PAUSED RunResponse up
     * to the team-level row AFTER dispatch (member ran, member's tool path paused).
     *
     * <p>Wire shape:
     * <ul>
     *   <li>{@code type} = {@link RequiredActionType#TEAM_MEMBER_DISPATCH_APPROVAL}</li>
     *   <li>{@code tool} carries the {@code teamId} (overloaded for wire-thrift; the
     *       resume handler reads from {@code human_review_pending.options} for the
     *       authoritative value)</li>
     *   <li>{@code sourceAgentId} carries the {@code memberAgentId} that would have
     *       been dispatched (mirrors {@code TOOL_APPROVAL.tool} = the gated target)</li>
     *   <li>{@code approvalId} = the {@code human_review_pending.id}</li>
     * </ul>
     */
    public static RequiredAction teamMemberDispatchApproval(String teamId, String memberAgentId,
                                                            String pendingId,
                                                            String traceId, String reasoningLineage,
                                                            String dagContext) {
        return new RequiredAction(RequiredActionType.TEAM_MEMBER_DISPATCH_APPROVAL,
                teamId, null, pendingId,
                memberAgentId, null, null, null, null,
                traceId, reasoningLineage, dagContext,
                null);
    }

    /**
     * Wraps a child member's RequiredAction with a team-level pointer. The team's row
     * carries the same TOOL_APPROVAL / SWARM_ESCALATION_APPROVAL payload as the child,
     * plus pausedChildRunId so callers know which member to resume. See Tier 2.5 F2.
     */
    public static RequiredAction teamMemberPause(RequiredAction childPayload, String pausedChildRunId) {
        return new RequiredAction(
                childPayload.type(),
                childPayload.tool(), childPayload.args(), childPayload.approvalId(),
                childPayload.sourceAgentId(), childPayload.targetAgentId(),
                childPayload.sourceTier(), childPayload.targetTier(), childPayload.escalationId(),
                childPayload.traceId(), childPayload.reasoningLineage(), childPayload.dagContext(),
                pausedChildRunId);
    }
}
