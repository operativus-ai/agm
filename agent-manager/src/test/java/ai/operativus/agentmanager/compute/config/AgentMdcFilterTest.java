package ai.operativus.agentmanager.compute.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentMdcFilter}. Verifies:
 * - Static MDC population and cleanup lifecycle
 * - Individual field setters (setAgentId, setPhase)
 * - Null-safe handling when ScopedValues are unbound
 * - Complete teardown preventing Virtual Thread MDC leaks
 */
class AgentMdcFilterTest {

    @BeforeEach
    void setUp() {
        AgentMdcFilter.clearMdc();
    }

    @AfterEach
    void tearDown() {
        AgentMdcFilter.clearMdc();
    }

    @Test
    @DisplayName("setAgentId populates MDC_AGENT_ID")
    void testSetAgentId() {
        AgentMdcFilter.setAgentId("weather-agent");
        assertThat(MDC.get(AgentMdcFilter.MDC_AGENT_ID)).isEqualTo("weather-agent");
    }

    @Test
    @DisplayName("setAgentId with null does NOT populate MDC")
    void testSetAgentIdNull() {
        AgentMdcFilter.setAgentId(null);
        assertThat(MDC.get(AgentMdcFilter.MDC_AGENT_ID)).isNull();
    }

    @Test
    @DisplayName("setAgentId with blank string does NOT populate MDC")
    void testSetAgentIdBlank() {
        AgentMdcFilter.setAgentId("   ");
        assertThat(MDC.get(AgentMdcFilter.MDC_AGENT_ID)).isNull();
    }

    @Test
    @DisplayName("setPhase populates MDC_PHASE")
    void testSetPhase() {
        AgentMdcFilter.setPhase("LLM_RPC_START");
        assertThat(MDC.get(AgentMdcFilter.MDC_PHASE)).isEqualTo("LLM_RPC_START");
    }

    @Test
    @DisplayName("setPhase with null does NOT populate MDC")
    void testSetPhaseNull() {
        AgentMdcFilter.setPhase(null);
        assertThat(MDC.get(AgentMdcFilter.MDC_PHASE)).isNull();
    }

    @Test
    @DisplayName("clearMdc removes ALL agentic MDC keys")
    void testClearMdc() {
        AgentMdcFilter.setAgentId("test-agent");
        AgentMdcFilter.setPhase("TOOL_INVOCATION");
        MDC.put(AgentMdcFilter.MDC_RUN_ID, "run-123");
        MDC.put(AgentMdcFilter.MDC_SESSION_ID, "session-456");
        MDC.put(AgentMdcFilter.MDC_USER_ID, "user-789");
        MDC.put(AgentMdcFilter.MDC_ORG_ID, "org-abc");
        MDC.put(AgentMdcFilter.MDC_ORCHESTRATION_DEPTH, "2");

        AgentMdcFilter.clearMdc();

        assertThat(MDC.get(AgentMdcFilter.MDC_RUN_ID)).isNull();
        assertThat(MDC.get(AgentMdcFilter.MDC_SESSION_ID)).isNull();
        assertThat(MDC.get(AgentMdcFilter.MDC_AGENT_ID)).isNull();
        assertThat(MDC.get(AgentMdcFilter.MDC_USER_ID)).isNull();
        assertThat(MDC.get(AgentMdcFilter.MDC_ORG_ID)).isNull();
        assertThat(MDC.get(AgentMdcFilter.MDC_PHASE)).isNull();
        assertThat(MDC.get(AgentMdcFilter.MDC_ORCHESTRATION_DEPTH)).isNull();
    }

    @Test
    @DisplayName("populateMdc does not throw when ScopedValues are unbound")
    void testPopulateMdcWithUnboundScopedValues() {
        // ScopedValues are not bound in test context — should gracefully default
        AgentMdcFilter.populateMdc();
        // Should not throw; values will either be null or "system" for userId
        assertThat(MDC.get(AgentMdcFilter.MDC_RUN_ID)).isNull();
    }

    @Test
    @DisplayName("MDC key constants match logback-spring.xml configuration")
    void testMdcKeyConstants() {
        assertThat(AgentMdcFilter.MDC_RUN_ID).isEqualTo("runId");
        assertThat(AgentMdcFilter.MDC_SESSION_ID).isEqualTo("sessionId");
        assertThat(AgentMdcFilter.MDC_AGENT_ID).isEqualTo("agentId");
        assertThat(AgentMdcFilter.MDC_USER_ID).isEqualTo("userId");
        assertThat(AgentMdcFilter.MDC_ORG_ID).isEqualTo("orgId");
        assertThat(AgentMdcFilter.MDC_PHASE).isEqualTo("phase");
        assertThat(AgentMdcFilter.MDC_ORCHESTRATION_DEPTH).isEqualTo("orchestrationDepth");
    }
}
