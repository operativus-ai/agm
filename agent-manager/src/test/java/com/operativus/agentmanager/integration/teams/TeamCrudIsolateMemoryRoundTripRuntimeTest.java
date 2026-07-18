package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.core.model.TeamDTO;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: §9 MEM-2 controller-side wire-shape coverage. Pins that
 * {@code Team.isolateMemory} round-trips intact through {@code POST /api/v1/teams},
 * {@code PUT /api/v1/teams/{id}}, and {@code GET /api/v1/teams/{id}} — and that the
 * established null-as-keep semantics are honoured (a subsequent PUT omitting the field
 * preserves the prior value, not silently flipping it to false).
 *
 * <p><b>Discovery context (Issue #2 from {@code docs/plans/agm-clear-out.md}):</b> when
 * this test was first written the {@code TeamDTO} record did NOT carry the
 * {@code isolateMemory} field. The DTO addition shipped in the same PR as this test;
 * see the spec doc's T006 for the rationale. PR #239's {@code TeamIsolateMemoryRuntimeTest}
 * pins the orchestrator-side wiring (Team entity → AgentDefinition → orchestrator); this
 * test pins the controller-side wiring (HTTP wire shape → DTO → entity).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class TeamCrudIsolateMemoryRoundTripRuntimeTest extends BaseIntegrationTest {

    private HttpHeaders auth;

    @BeforeEach
    void setUp() {
        truncateDatabase();
        auth = authenticateAs(
                "team-iso-admin", "team-iso-admin@test.local", "pass-tia-1234",
                List.of("ROLE_ADMIN"));
    }

    @Test
    void postWithIsolateMemoryTrue_persistsAndGetReturnsTrue() {
        Map<String, Object> body = Map.of(
                "name", "iso-team-true",
                "teamMode", "SEQUENTIAL",
                "isolateMemory", true);

        ResponseEntity<TeamDTO> create = rest.exchange(
                url("/api/v1/teams"), HttpMethod.POST,
                new HttpEntity<>(body, auth), TeamDTO.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = create.getBody().id();
        assertThat(create.getBody().isolateMemory())
                .as("POST response carries the persisted flag")
                .isTrue();

        ResponseEntity<TeamDTO> get = rest.exchange(
                url("/api/v1/teams/" + id), HttpMethod.GET,
                new HttpEntity<>(auth), TeamDTO.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody().isolateMemory())
                .as("GET round-trips the field intact through the JSON wire format")
                .isTrue();
    }

    @Test
    void postWithIsolateMemoryAbsent_defaultsToFalseFromEntity() {
        Map<String, Object> body = Map.of(
                "name", "iso-team-default",
                "teamMode", "SEQUENTIAL");

        ResponseEntity<TeamDTO> create = rest.exchange(
                url("/api/v1/teams"), HttpMethod.POST,
                new HttpEntity<>(body, auth), TeamDTO.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody().isolateMemory())
                .as("absent field on POST → entity default FALSE → wire FALSE (NOT null)")
                .isFalse();
    }

    @Test
    void patchToggleAndOmissionFollowsNullAsKeepSemantics() {
        // The TeamsController exposes UPDATE as @PatchMapping, not @PutMapping —
        // partial-update semantics are the project's convention. The test name and
        // the verb used to drive the controller therefore say PATCH; the null-as-keep
        // contract is identical to what a PUT-with-update-rule would imply.
        // Create with isolateMemory=true.
        Map<String, Object> create = Map.of(
                "name", "iso-team-toggle",
                "teamMode", "SEQUENTIAL",
                "isolateMemory", true);
        ResponseEntity<TeamDTO> created = rest.exchange(
                url("/api/v1/teams"), HttpMethod.POST,
                new HttpEntity<>(create, auth), TeamDTO.class);
        String id = created.getBody().id();
        assertThat(created.getBody().isolateMemory()).isTrue();

        // PATCH that OMITS isolateMemory must preserve the prior TRUE (null-as-keep).
        Map<String, Object> patchKeep = Map.of("name", "iso-team-toggle-renamed");
        ResponseEntity<TeamDTO> kept = rest.exchange(
                url("/api/v1/teams/" + id), HttpMethod.PATCH,
                new HttpEntity<>(patchKeep, auth), TeamDTO.class);
        assertThat(kept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kept.getBody().isolateMemory())
                .as("PATCH update follows null-as-keep — omitting field preserves prior TRUE")
                .isTrue();
        assertThat(kept.getBody().name()).isEqualTo("iso-team-toggle-renamed");

        // PATCH that EXPLICITLY sets false flips to false.
        Map<String, Object> patchFalse = Map.of("isolateMemory", false);
        ResponseEntity<TeamDTO> flipped = rest.exchange(
                url("/api/v1/teams/" + id), HttpMethod.PATCH,
                new HttpEntity<>(patchFalse, auth), TeamDTO.class);
        assertThat(flipped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(flipped.getBody().isolateMemory())
                .as("explicit FALSE in PATCH body flips the persisted state")
                .isFalse();
    }
}
