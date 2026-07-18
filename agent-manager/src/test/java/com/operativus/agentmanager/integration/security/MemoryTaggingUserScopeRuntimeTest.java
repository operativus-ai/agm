package com.operativus.agentmanager.integration.security;

import com.operativus.agentmanager.control.repository.AgenticMemoryRepository;
import com.operativus.agentmanager.core.entity.AgenticMemoryEntity;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-user access model on {@code MemoryTaggingController} (follow-up to the
 * tenant-scoping fix). Agentic memory is personal: a non-admin caller may only reach their
 * OWN userId's memories (timeline/export/tags); another user's data is 403. A per-org ADMIN
 * may reach any user within their org (support/ops override).
 *
 * <p>Both callers live in DEFAULT_SYSTEM_ORG (authenticateAs / registerLoginWithOrg defaults);
 * unique tagged userIds keep assertions independent of any other rows in that org.
 */
@Tag("integration")
public class MemoryTaggingUserScopeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private AgenticMemoryRepository memoryRepository;

    @Test
    void nonAdminMayOnlyReachOwnMemories_adminMayReachAnyUserInOrg() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String selfUser = "mem-self-" + tag;
        String otherUser = "mem-other-" + tag;
        String org = "DEFAULT_SYSTEM_ORG";

        UUID memSelf = seed(selfUser, org, "self secret", List.of("s"));
        UUID memOther = seed(otherUser, org, "other secret", List.of("o"));

        // ROLE_USER caller whose JWT subject == selfUser, bound to DEFAULT_SYSTEM_ORG.
        HttpHeaders user = authenticateAs(selfUser, selfUser + "@test.local", "pass-user-1234", List.of("ROLE_USER"));

        // --- non-admin: own data OK ---
        ResponseEntity<List<Map<String, Object>>> ownTimeline = rest.exchange(
                url("/api/memories/timeline/" + selfUser), HttpMethod.GET, new HttpEntity<>(user), JSON_LIST);
        assertEquals(HttpStatus.OK, ownTimeline.getStatusCode());
        assertEquals(1, ownTimeline.getBody().size());
        assertEquals(memSelf.toString(), String.valueOf(ownTimeline.getBody().get(0).get("id")));

        ResponseEntity<Void> tagOwn = rest.exchange(
                url("/api/memories/" + memSelf + "/tags"), HttpMethod.PUT,
                new HttpEntity<>(List.of("mine"), user), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, tagOwn.getStatusCode());

        // --- non-admin: another user's data → 403 ---
        ResponseEntity<List<Map<String, Object>>> otherTimeline = rest.exchange(
                url("/api/memories/timeline/" + otherUser), HttpMethod.GET, new HttpEntity<>(user), JSON_LIST);
        assertEquals(HttpStatus.FORBIDDEN, otherTimeline.getStatusCode(),
                "non-admin must not read another user's timeline");

        ResponseEntity<Void> otherExport = rest.exchange(
                url("/api/memories/export/" + otherUser), HttpMethod.GET, new HttpEntity<>(user), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, otherExport.getStatusCode(),
                "non-admin must not export another user's memories");

        ResponseEntity<Void> tagOther = rest.exchange(
                url("/api/memories/" + memOther + "/tags"), HttpMethod.PUT,
                new HttpEntity<>(List.of("pwned"), user), Void.class);
        assertEquals(HttpStatus.FORBIDDEN, tagOther.getStatusCode(),
                "non-admin must not tag another user's memory");
        assertEquals(List.of("o"), memoryRepository.findById(memOther).orElseThrow().getTopics(),
                "other user's topics must be unchanged after the blocked write");

        // --- per-org ADMIN override: may reach another user in the same org ---
        HttpHeaders admin = registerLoginWithOrg("mem-admin-" + tag, org);
        ResponseEntity<List<Map<String, Object>>> adminViewsOther = rest.exchange(
                url("/api/memories/timeline/" + otherUser), HttpMethod.GET, new HttpEntity<>(admin), JSON_LIST);
        assertEquals(HttpStatus.OK, adminViewsOther.getStatusCode(), "ADMIN may view any user in their org");
        assertTrue(adminViewsOther.getBody().stream().anyMatch(r -> memOther.toString().equals(String.valueOf(r.get("id")))),
                "ADMIN timeline for the other user includes that user's memory");
    }

    private UUID seed(String userId, String orgId, String memory, List<String> topics) {
        AgenticMemoryEntity e = new AgenticMemoryEntity();
        e.setMemoryId(UUID.randomUUID());
        e.setUserId(userId);
        e.setOrgId(orgId);
        e.setMemory(memory);
        e.setTopics(topics);
        e.setMemoryTier(AgenticMemoryEntity.MemoryTier.USER_MEMORY);
        return memoryRepository.save(e).getMemoryId();
    }
}
