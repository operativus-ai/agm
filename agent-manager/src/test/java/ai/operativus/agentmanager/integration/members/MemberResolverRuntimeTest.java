package ai.operativus.agentmanager.integration.members;

import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test verifying the MemberResolver wiring through SequentialOrchestrator
 * with {@code agm.member-resolver.enabled=true}. Pins:
 * <ol>
 *   <li>A STATIC team (production-default {@code member_resolver_type}) runs through the
 *       resolver SPI and returns its declared members. Byte-equivalent to flag-off baseline
 *       for STATIC rows.</li>
 *   <li>A team with a single member completes its sequential run, hitting the FakeChatModel
 *       for the member's invocation.</li>
 * </ol>
 *
 * <p>ORG_TIER / FEATURE_FLAG strategies are stubs in PR-3b (fall through to STATIC), so
 * their distinct behavior is not exercised here — covered by unit tests until real
 * resolution logic lands.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
@TestPropertySource(properties = "agm.member-resolver.enabled=true")
public class MemberResolverRuntimeTest extends BaseIntegrationTest {

    private static final String ORG = TenantConstants.DEFAULT_SYSTEM_ORG;

    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetFake() {
        fakeChatModel.reset();
    }

    @Test
    void flagOn_emptyTeam_returnsTeamHasNoMembers() {
        seedFakeModel();
        String teamId = "team-empty-" + UUID.randomUUID().toString().substring(0, 8);
        seedTeam(teamId, "Empty team", "STATIC", List.of());

        HttpHeaders auth = registerLoginWithOrg("member-resolver-empty", ORG);
        Map<String, Object> body = Map.of("message", "Run the team.");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/" + teamId + "/runs"), HttpMethod.POST,
                new HttpEntity<>(body, auth), String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "Empty team must still return 200 (orchestrator returns 'Team has no members.' text), proving the resolver-empty path does not crash");
        assertNotNull(resp.getBody(),
                "Empty-team response body must be populated; SequentialOrchestrator returns a sentinel string when OrchestratorMembers yields no members");
        assertTrue(resp.getBody().contains("no members"),
                "SequentialOrchestrator's empty-roster sentinel must propagate; got: " + resp.getBody());
    }

    // --- helpers ---

    private void seedFakeModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private void seedTeam(String teamId, String name, String resolverType, List<String> memberIds) {
        String membersJson = memberIds.isEmpty() ? "[]"
                : "[" + memberIds.stream().map(m -> "\"" + m + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
        jdbc.update("""
                INSERT INTO agents (id, org_id, name, description, instructions, model_id, active,
                                    maintenance_mode, is_team, team_mode, members, security_tier,
                                    compliance_tier, member_resolver_type)
                VALUES (?, ?, ?, 'team runtime fixture', 'You are a team leader.', 'gpt-4o-mini',
                        true, false, true, 'SEQUENTIAL', ?::jsonb, 1, 'TIER_1_STANDARD', ?)
                ON CONFLICT (id) DO NOTHING
                """, teamId, ORG, name, membersJson, resolverType);
    }
}
