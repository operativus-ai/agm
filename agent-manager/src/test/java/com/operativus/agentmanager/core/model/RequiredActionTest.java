package com.operativus.agentmanager.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequiredActionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void toolApprovalFactory_pausedChildRunIdIsNull_andElidedFromJson() throws Exception {
        RequiredAction ra = RequiredAction.toolApproval(
                "delete_file", "{}", "approval-1", "trace", "lineage", "depth");

        assertNull(ra.pausedChildRunId());
        String json = JSON.writeValueAsString(ra);
        assertFalse(json.contains("pausedChildRunId"),
                "@JsonInclude(NON_NULL) must elide pausedChildRunId on single-agent paths; was: " + json);
    }

    @Test
    void swarmEscalationFactory_pausedChildRunIdIsNull_andElidedFromJson() throws Exception {
        RequiredAction ra = RequiredAction.swarmEscalation(
                "src", "tgt", 1, 2, "esc-1", "trace", "lineage", "depth");

        assertNull(ra.pausedChildRunId());
        String json = JSON.writeValueAsString(ra);
        assertFalse(json.contains("pausedChildRunId"),
                "@JsonInclude(NON_NULL) must elide pausedChildRunId on single-agent paths; was: " + json);
    }

    @Test
    void teamMemberPauseFactory_copiesChildPayloadAndStampsRunId() {
        RequiredAction child = RequiredAction.toolApproval(
                "drop_table", "{\"name\":\"users\"}", "approval-99", "trace-9", "lineage-9", "depth=2");

        RequiredAction lifted = RequiredAction.teamMemberPause(child, "child-run-456");

        assertEquals(RequiredActionType.TOOL_APPROVAL, lifted.type());
        assertEquals("drop_table", lifted.tool());
        assertEquals("approval-99", lifted.approvalId());
        assertEquals("trace-9", lifted.traceId());
        assertEquals("child-run-456", lifted.pausedChildRunId());
    }

    @Test
    void teamMemberPauseFactory_preservesSwarmEscalationVariant() {
        RequiredAction childEscalation = RequiredAction.swarmEscalation(
                "src", "tgt", 1, 3, "esc-7", "trace", "lineage", "depth");

        RequiredAction lifted = RequiredAction.teamMemberPause(childEscalation, "child-run-zzz");

        assertEquals(RequiredActionType.SWARM_ESCALATION_APPROVAL, lifted.type());
        assertEquals("src", lifted.sourceAgentId());
        assertEquals(Integer.valueOf(3), lifted.targetTier());
        assertEquals("child-run-zzz", lifted.pausedChildRunId());
    }
}
