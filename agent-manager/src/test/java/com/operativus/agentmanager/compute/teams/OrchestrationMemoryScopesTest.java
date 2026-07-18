package com.operativus.agentmanager.compute.teams;

import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin the §9 MEM-2 conversationId-derivation rules so the orchestrators
 * that call this helper produce a consistent format. Receivers (PersistentChatMemory) treat the
 * derived id as a session id, which means each (team, member) pair gets its own session row;
 * the format must therefore stay stable so cleanup/retention queries can match.
 */
class OrchestrationMemoryScopesTest {

    @Test
    void memberConversationId_withIsolateFalse_returnsBareSessionId() {
        AgentDefinition team = team(false);
        assertThat(OrchestrationMemoryScopes.memberConversationId(team, "sess-1", "m-a"))
                .isEqualTo("sess-1");
    }

    @Test
    void memberConversationId_withIsolateTrue_appendsMemberScope() {
        AgentDefinition team = team(true);
        assertThat(OrchestrationMemoryScopes.memberConversationId(team, "sess-1", "m-a"))
                .isEqualTo("sess-1::member::m-a");
    }

    @Test
    void memberConversationId_perMemberKeysAreDistinct() {
        AgentDefinition team = team(true);
        String a = OrchestrationMemoryScopes.memberConversationId(team, "sess-1", "m-a");
        String b = OrchestrationMemoryScopes.memberConversationId(team, "sess-1", "m-b");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).startsWith("sess-1::member::");
        assertThat(b).startsWith("sess-1::member::");
    }

    @Test
    void memberConversationId_isolateNullTreatedAsFalse() {
        AgentDefinition team = team(null);
        assertThat(OrchestrationMemoryScopes.memberConversationId(team, "sess-1", "m-a"))
                .isEqualTo("sess-1");
    }

    @Test
    void memberConversationId_nullRootAgent_returnsBareSessionId() {
        assertThat(OrchestrationMemoryScopes.memberConversationId(null, "sess-1", "m-a"))
                .isEqualTo("sess-1");
    }

    @Test
    void memberConversationId_nullMemberId_returnsBareSessionId() {
        AgentDefinition team = team(true);
        assertThat(OrchestrationMemoryScopes.memberConversationId(team, "sess-1", null))
                .isEqualTo("sess-1");
    }

    @Test
    void memberConversationId_nullSession_propagatesNull_neverInventsOne() {
        AgentDefinition team = team(true);
        assertThat(OrchestrationMemoryScopes.memberConversationId(team, null, "m-a")).isNull();
    }

    @Test
    void rootSessionId_stripsMemberScope() {
        assertThat(OrchestrationMemoryScopes.rootSessionId("sess-1::member::m-a")).isEqualTo("sess-1");
    }

    @Test
    void rootSessionId_bareSessionIsUnchanged() {
        assertThat(OrchestrationMemoryScopes.rootSessionId("sess-1")).isEqualTo("sess-1");
    }

    @Test
    void rootSessionId_nullIsNull() {
        assertThat(OrchestrationMemoryScopes.rootSessionId(null)).isNull();
    }

    @Test
    void rootSessionId_inverts_memberConversationId() {
        AgentDefinition team = team(true);
        String scoped = OrchestrationMemoryScopes.memberConversationId(team, "sess-xyz", "m-a");
        assertThat(OrchestrationMemoryScopes.rootSessionId(scoped)).isEqualTo("sess-xyz");
    }

    private static AgentDefinition team(Boolean isolate) {
        return new AgentDefinition(
                "team-1", "T", "D", "I", "gpt-x",
                null, null, null, null,
                false, true, "SEQUENTIAL",
                java.util.List.of("m-a", "m-b"),
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, com.operativus.agentmanager.core.entity.ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                isolate, null, null, null);
    }
}
