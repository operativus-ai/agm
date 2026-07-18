package ai.operativus.agentmanager.compute.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.exception.TeamMemberPausedException;
import ai.operativus.agentmanager.core.model.RequiredAction;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.enums.RunStatus;

import java.util.Map;

/**
 * Domain Responsibility: Inspects member-run results inside multi-agent strategies for
 *     terminal HITL signals (PAUSED status with a {@link RequiredAction} payload) and
 *     surfaces them via {@link TeamMemberPausedException} so the team-branch catch in
 *     {@code AgentService.run} can lift the child's HITL state up to the team-level
 *     row. Pre-Tier-2.5-F2, every strategy silently dropped PAUSED status by reading
 *     only {@code .content()}.
 *
 * State: Stateless (private constructor; only static helpers).
 */
public final class MemberRunGuard {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MemberRunGuard() {
    }

    /**
     * Returns the response unchanged when the member completed normally; throws
     * {@link TeamMemberPausedException} when the member paused so the strategy
     * unwinds out to the team branch.
     */
    public static RunResponse requireNotPaused(RunResponse memberResponse, String memberAgentId) {
        if (memberResponse == null) {
            return null;
        }
        if (memberResponse.status() == RunStatus.PAUSED) {
            RequiredAction ra = extractRequiredAction(memberResponse);
            throw new TeamMemberPausedException(memberResponse.runId(), memberAgentId, ra);
        }
        return memberResponse;
    }

    /**
     * Pulls the typed {@link RequiredAction} out of {@code RunResponse.metadata().get("requiredAction")}.
     * In-process the value is already a typed record (set at the single-agent catches in
     * {@code AgentService.run}). If a future change serializes RunResponse between the
     * member and the strategy, the value lands as a {@code Map}; the Jackson {@code convertValue}
     * fallback re-hydrates it. Returns null if absent or unconvertible.
     */
    public static RequiredAction extractRequiredAction(RunResponse memberResponse) {
        if (memberResponse == null || memberResponse.metadata() == null) {
            return null;
        }
        Object raw = memberResponse.metadata().get("requiredAction");
        if (raw == null) {
            return null;
        }
        if (raw instanceof RequiredAction typed) {
            return typed;
        }
        if (raw instanceof Map<?, ?>) {
            try {
                return JSON.convertValue(raw, RequiredAction.class);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
