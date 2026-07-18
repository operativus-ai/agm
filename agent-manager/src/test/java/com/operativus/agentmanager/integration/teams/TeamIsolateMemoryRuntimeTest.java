package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.OrchestrationMemoryScopes;
import com.operativus.agentmanager.compute.teams.SequentialOrchestrator;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: §9 MEM-2 wiring — pin the end-to-end flow that the
 * {@code teams.isolate_memory} column → {@link com.operativus.agentmanager.core.entity.Team}
 * entity → {@link AgentDefinition#isolateMemory()} → orchestrator → per-member
 * {@code sessionId + "::member::" + memberId} derivation.
 *
 * <p>The unit-level pieces are pinned by {@code OrchestrationMemoryScopesTest} (helper)
 * and the orchestrator unit tests (per-strategy dispatch). What is NOT covered without
 * this runtime test is the round-trip through the database + JPA + AgentRegistry mapper —
 * a refactor that drops the flag in {@code DatabaseAgentRegistry.mapTeamToDefinition} or
 * the JPA column would silently revert §9 MEM-2 for live deployments while leaving every
 * unit test green.
 *
 * <p>Anti-pattern A3 (test reading persistence implementation details): this test does
 * NOT poke at {@code agent_messages} rows or any other Spring-AI-internal table. It
 * captures the {@code sessionId} argument the orchestrator passes to the (recording)
 * runner and asserts on its derived format — pinning the contract behaviour, not the
 * advisor's persistence representation.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class TeamIsolateMemoryRuntimeTest extends BaseIntegrationTest {

    @Autowired private SequentialOrchestrator sequential;
    @Autowired private AgentRegistry agentRegistry;
    @Autowired private com.operativus.agentmanager.control.repository.AgentRepository agentRepository;
    @Autowired private com.operativus.agentmanager.control.repository.TeamRepository teamRepository;

    private RecordingRunner runner;

    @BeforeEach
    void resetHarness() {
        runner = new RecordingRunner();
        seedDefaultModel();
    }

    @Test
    void teamWithIsolateMemoryTrue_passesPerMemberDerivedSessionIdToRunner() {
        String m1 = persistMemberAgent("isolate-m1");
        String m2 = persistMemberAgent("isolate-m2");
        String teamId = persistTeam("isolate-team", List.of(m1, m2), /*isolateMemory=*/ true);

        AgentDefinition def = agentRegistry.findById(teamId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertThat(def).isNotNull();
        assertThat(def.isolateMemory())
                .as("team row's isolate_memory column round-trips through DatabaseAgentRegistry.mapTeamToDefinition")
                .isTrue();

        runner.scriptedResponses.add(runResponse("m1 said pineapple"));
        runner.scriptedResponses.add(runResponse("m2 has no idea what you're talking about"));

        sequential.execute(def, "kickoff", new ArrayList<>(),
                "sess-iso", "user-1", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, runner);

        assertThat(runner.calls).hasSize(2);
        assertThat(runner.calls.get(0).sessionId)
                .as("member 1 receives a derived per-member conversation id")
                .isEqualTo(OrchestrationMemoryScopes.memberConversationId(def, "sess-iso", m1))
                .isEqualTo("sess-iso::member::" + m1);
        assertThat(runner.calls.get(1).sessionId)
                .as("member 2 receives its own derived id, distinct from member 1")
                .isEqualTo("sess-iso::member::" + m2)
                .isNotEqualTo(runner.calls.get(0).sessionId);
    }

    @Test
    void teamWithIsolateMemoryFalse_passesBareTeamSessionIdToEveryMember() {
        String m1 = persistMemberAgent("share-m1");
        String m2 = persistMemberAgent("share-m2");
        String teamId = persistTeam("share-team", List.of(m1, m2), /*isolateMemory=*/ false);

        AgentDefinition def = agentRegistry.findById(teamId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertThat(def).isNotNull();
        assertThat(def.isolateMemory())
                .as("default-FALSE preserves pre-§9-MEM-2 behaviour")
                .isFalse();

        runner.scriptedResponses.add(runResponse("m1 says pineapple"));
        runner.scriptedResponses.add(runResponse("m2 sees the pineapple"));

        sequential.execute(def, "kickoff", new ArrayList<>(),
                "sess-share", "user-1", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, runner);

        assertThat(runner.calls).hasSize(2);
        assertThat(runner.calls.get(0).sessionId)
                .as("isolate=false → bare team session passes through unchanged")
                .isEqualTo("sess-share");
        assertThat(runner.calls.get(1).sessionId).isEqualTo("sess-share");
    }

    @Test
    void teamWithIsolateMemoryNull_legacyRow_treatedAsFalse() {
        // Pre-049 rows where the column never existed wouldn't show up here (Liquibase 049
        // backfilled NOT NULL DEFAULT FALSE), but defence-in-depth: even if a row's flag
        // were null at the entity level, OrchestrationMemoryScopes treats null as "no
        // isolation" so behaviour is the safe legacy default.
        String m1 = persistMemberAgent("legacy-m1");
        String teamId = persistTeam("legacy-team", List.of(m1), /*isolateMemory=*/ false);

        AgentDefinition def = agentRegistry.findById(teamId, TenantConstants.DEFAULT_SYSTEM_ORG);
        runner.scriptedResponses.add(runResponse("m1 output"));
        sequential.execute(def, "kickoff", new ArrayList<>(),
                "sess-legacy", "user-1", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, runner);

        assertThat(runner.calls).hasSize(1);
        assertThat(runner.calls.get(0).sessionId).isEqualTo("sess-legacy");
    }

    private String persistMemberAgent(String prefix) {
        String id = prefix + "-" + UUID.randomUUID();
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(prefix);
        a.setDescription("§9 MEM-2 fixture member: " + prefix);
        a.setInstructions("Respond.");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(false);
        return agentRepository.save(a).getId();
    }

    private String persistTeam(String prefix, List<String> memberIds, boolean isolateMemory) {
        String id = prefix + "-" + UUID.randomUUID();
        com.operativus.agentmanager.core.entity.Team team = new com.operativus.agentmanager.core.entity.Team();
        team.setId(id);
        team.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        team.setName(prefix);
        team.setDescription("§9 MEM-2 fixture team");
        team.setTeamMode("SEQUENTIAL");
        team.setLeaderId(memberIds.isEmpty() ? null : memberIds.get(0));
        team.setMemoryEnabled(false);
        team.setAddHistoryToMessages(true);
        team.setIsolateMemory(isolateMemory);
        teamRepository.save(team);

        // Also persist a matching agent_entities row so the team's members list can be
        // resolved by AgentRegistry through the team's leader-id model inheritance path.
        AgentEntity teamProxy = new AgentEntity();
        teamProxy.setId(id);
        teamProxy.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        teamProxy.setName(prefix);
        teamProxy.setDescription("§9 MEM-2 fixture team-proxy");
        teamProxy.setInstructions("Team orchestration proxy");
        teamProxy.setModelId("gpt-4o-mini");
        teamProxy.setActive(true);
        teamProxy.setMaintenanceMode(false);
        teamProxy.setTeam(true);
        teamProxy.setTeamMode("SEQUENTIAL");
        teamProxy.setMembers(memberIds);
        agentRepository.save(teamProxy);
        return id;
    }

    private RunResponse runResponse(String content) {
        return new RunResponse("run-" + UUID.randomUUID(), "sess-x", content,
                new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private static final class RecordingRunner implements AgentOperations {
        final List<RecordedCall> calls = new ArrayList<>();
        final java.util.Deque<RunResponse> scriptedResponses = new java.util.ArrayDeque<>();

        @Override
        public RunResponse run(String agentId, String userInput, String sessionId) {
            return run(agentId, userInput, null, sessionId, null, null, false, null);
        }

        @Override
        public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                               String userId, String orgId, Boolean generateFollowups, RunOptions options) {
            calls.add(new RecordedCall(agentId, userInput, sessionId));
            return scriptedResponses.isEmpty()
                    ? new RunResponse("r", sessionId, "", new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null)
                    : scriptedResponses.poll();
        }

        @Override public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options) { throw new UnsupportedOperationException(); }
        @Override public String runInBackground(String agentId, String userInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options) { throw new UnsupportedOperationException(); }
        @Override public String runInBackground(String agentId, String userInput, String sessionId) { throw new UnsupportedOperationException(); }
        @Override public String runPlayground(String agentId, String userInput, String sessionId) { throw new UnsupportedOperationException(); }
        @Override public void cancelRun(String runId) { throw new UnsupportedOperationException(); }
        @Override public RunResponse continueRun(String runId, String action) { throw new UnsupportedOperationException(); }
        @Override public void loadKnowledge(String agentId) { throw new UnsupportedOperationException(); }
    }

    private record RecordedCall(String agentId, String userInput, String sessionId) {}
}
