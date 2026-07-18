package ai.operativus.agentmanager.compute.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.exception.TeamMemberPausedException;
import ai.operativus.agentmanager.core.model.RequiredAction;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemberRunGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void requireNotPaused_pausedResponse_throwsTeamMemberPausedException() {
        RequiredAction ra = RequiredAction.toolApproval(
                "delete_file", "{\"path\":\"/x\"}", "approval-1", "trace", "lineage", "depth");
        Map<String, Object> meta = new HashMap<>();
        meta.put("requiredAction", ra);

        RunResponse paused = new RunResponse("child-id", "sess", "tool paused",
                meta, Collections.emptyList(), Collections.emptyList(),
                RunStatus.PAUSED, null);

        TeamMemberPausedException ex = assertThrows(TeamMemberPausedException.class, () ->
                MemberRunGuard.requireNotPaused(paused, "memberA"));
        assertEquals("child-id", ex.getPausedRunId());
        assertEquals("memberA", ex.getPausedAgentId());
        assertEquals("delete_file", ex.getRequiredAction().tool());
    }

    @Test
    void requireNotPaused_completedResponse_returnsUnchanged() {
        RunResponse completed = new RunResponse("run-1", "sess", "ok",
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                RunStatus.COMPLETED, null);

        RunResponse out = MemberRunGuard.requireNotPaused(completed, "memberB");
        assertSame(completed, out);
    }

    @Test
    void requireNotPaused_pausedWithoutRequiredAction_throwsWithNullPayload() {
        RunResponse paused = new RunResponse("child-id", "sess", "paused with no metadata",
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                RunStatus.PAUSED, null);

        TeamMemberPausedException ex = assertThrows(TeamMemberPausedException.class, () ->
                MemberRunGuard.requireNotPaused(paused, "memberC"));
        assertNull(ex.getRequiredAction());
    }

    @Test
    void requireNotPaused_nullResponse_returnsNull() {
        // Defensive: don't NPE on a null member result.
        assertNull(MemberRunGuard.requireNotPaused(null, "memberX"));
    }

    @Test
    void extractRequiredAction_handlesJacksonDeserializedMap() throws Exception {
        // Simulate a serialization round-trip: typed record → JSON → Map.
        RequiredAction ra = RequiredAction.swarmEscalation(
                "src", "tgt", 1, 2, "esc-1", "trace", "lineage", "depth");
        String json = JSON.writeValueAsString(ra);
        Map<String, Object> mapForm = JSON.readValue(json, Map.class);

        Map<String, Object> meta = new HashMap<>();
        meta.put("requiredAction", mapForm);

        RunResponse paused = new RunResponse("child-id", "sess", "paused",
                meta, Collections.emptyList(), Collections.emptyList(),
                RunStatus.PAUSED, null);

        RequiredAction out = MemberRunGuard.extractRequiredAction(paused);
        assertNotNull(out);
        assertEquals("src", out.sourceAgentId());
        assertEquals(Integer.valueOf(2), out.targetTier());
    }
}
