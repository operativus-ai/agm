package com.operativus.agentmanager.integration.memory;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the MemoryController fix for the {@code ?userId=} cross-user enumeration
 * vulnerability in {@code GET /api/memories/stats} and {@code GET /api/memories/topics}.
 *
 * <p>Pre-fix the controller used
 * {@code SecurityContextUtils.resolveUserId(explicitUserId)} which trusts the
 * explicit param verbatim — any authenticated user could enumerate any other user's
 * memory stats / topics within their tenant by setting
 * {@code ?userId=victim-user}.
 *
 * <p>Post-fix, ROLE_USER callers have the param overridden to their own principal
 * id (their own memories only). ROLE_ADMIN retains full access. Same recipe as
 * SessionController G5 (#672).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class MemoryStatsUserEnumRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void memoryStats_roleUserCannotEnumerateOtherUsersMemoriesViaQueryParam() {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        // Attacker authenticated as ROLE_USER. The controller's post-fix override uses
        // UserDetailsImpl.getId().toString() (the UUID, not the username), so we look
        // up the registered user's id and use it as the user_id on the seeded memory.
        String attackerUsername = "memory-enum-attacker-" + tag;
        HttpHeaders attackerAuth = authenticateAs(
                attackerUsername,
                attackerUsername + "@test.local",
                "pass-memory-1234",
                List.of("ROLE_USER"));
        String attackerUserUuid = jdbc.queryForObject(
                "SELECT id::text FROM users WHERE username = ?", String.class, attackerUsername);

        // Victim user owns 3 memories. The attacker owns 1.
        String victimUserId = "victim-user-memory-" + tag;
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO agentic_memories (memory_id, memory, user_id, memory_tier, created_at, updated_at)
                    VALUES (?, ?, ?, 'USER_MEMORY', now(), now())
                    """, UUID.randomUUID(), "victim memory " + i, victimUserId);
        }
        jdbc.update("""
                INSERT INTO agentic_memories (memory_id, memory, user_id, memory_tier, created_at, updated_at)
                VALUES (?, ?, ?, 'USER_MEMORY', now(), now())
                """, UUID.randomUUID(), "attacker memory", attackerUserUuid);

        // Attacker tries the spoof: ?userId=victim-user.
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/memories/stats?userId=" + victimUserId),
                HttpMethod.GET, new HttpEntity<>(attackerAuth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Number totalMemories = (Number) resp.getBody().get("total_memories");

        // Post-fix the controller ignores the ?userId= param for ROLE_USER and scopes
        // to the attacker's own userId. The attacker has 1 memory; the victim has 3.
        // Pre-fix this assertion would fail with totalMemories == 3.
        assertEquals(1L, totalMemories.longValue(),
                "ROLE_USER attacker must see only their own memory count (1), NOT the victim's (3). "
                        + "Pre-fix the controller honored ?userId= and returned victim's stats. Got: "
                        + resp.getBody());
    }
}
