package ai.operativus.agentmanager.compute.teams;

import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;

/**
 * Domain Responsibility: §9 MEM-2 helper. Resolve the conversationId an orchestrator should
 * pass when invoking a team member's {@code AgentService.run}. When the team's
 * {@link AgentDefinition#isolateMemory()} flag is true, derive a stable per-member scope
 * so each member's {@code MessageChatMemoryAdvisor} keeps its own buffer; otherwise pass
 * the team's session id through unchanged so members share the team transcript.
 *
 * <p>State: Stateless (pure static). Tested directly without a Spring context.
 */
public final class OrchestrationMemoryScopes {

    /** Separator that does not appear in UUIDs or normal session-id formats so cleanup
     *  queries can split the prefix off later if ever needed. */
    static final String MEMBER_SCOPE_SEPARATOR = "::member::";

    private OrchestrationMemoryScopes() {}

    /**
     * @summary Resolve the conversationId to use for a team member's run.
     * @logic Returns {@code sessionId + "::member::" + memberId} when the team has
     *     {@code isolateMemory == true}, otherwise the bare {@code sessionId}.
     *     Null-safe: a null root agent or null memberId both yield {@code sessionId}.
     */
    public static String memberConversationId(AgentDefinition rootAgent, String sessionId, String memberId) {
        if (rootAgent == null || memberId == null) return sessionId;
        if (!Boolean.TRUE.equals(rootAgent.isolateMemory())) return sessionId;
        if (sessionId == null) return null; // never invent a session
        return sessionId + MEMBER_SCOPE_SEPARATOR + memberId;
    }

    /**
     * @summary Inverse of {@link #memberConversationId}: strip the {@code ::member::<id>} suffix to
     *     recover the root team session id. Returns {@code sessionId} unchanged when it carries no
     *     member scope (the common, non-isolated case). Null-safe.
     * @logic The separator is chosen to never appear in a UUID/normal session id, so the first
     *     occurrence is the scope boundary. Lets callers map a memory-isolated team member's derived
     *     scope back to the root team session id.
     */
    public static String rootSessionId(String sessionId) {
        if (sessionId == null) return null;
        int idx = sessionId.indexOf(MEMBER_SCOPE_SEPARATOR);
        return idx > 0 ? sessionId.substring(0, idx) : sessionId;
    }
}
