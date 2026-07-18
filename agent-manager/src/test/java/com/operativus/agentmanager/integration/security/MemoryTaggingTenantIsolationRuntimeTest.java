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
 * Pins the {@code MemoryTaggingController} tenant-scoping fix.
 *
 * <p>Pre-fix the controller had NO authorization gate and queried agentic memory with the
 * unscoped {@code findByUserId} / {@code findById}, so any authenticated caller could read
 * (timeline/export) or overwrite (tag) another org's memory rows by passing that org's
 * userId / memoryId. Post-fix every endpoint AND-filters on the caller's org
 * ({@link com.operativus.agentmanager.core.callback.AgentContextHolder}); cross-org reads
 * return empty and cross-org writes return 404.
 */
@Tag("integration")
public class MemoryTaggingTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private AgenticMemoryRepository memoryRepository;

    @Test
    void memoryTaggingIsTenantScoped_orgACannotReadOrWriteOrgBMemories() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-mem-A-" + tag;
        String orgB = "org-mem-B-" + tag;
        String userA = "mem-userA-" + tag;
        String userB = "mem-userB-" + tag;

        UUID memA = seedMemory(userA, orgA, "A's secret", List.of("old-a"));
        UUID memB = seedMemory(userB, orgB, "B's secret", List.of("old-b"));

        // Caller is in org A only (endpoints have no role gate — any authenticated user).
        HttpHeaders callerA = registerLoginWithOrg("mem-iso-A-" + tag, orgA);

        // 1. Cross-org WRITE blocked → 404, and B's row is untouched.
        ResponseEntity<Void> crossTag = rest.exchange(
                url("/api/memories/" + memB + "/tags"), HttpMethod.PUT,
                new HttpEntity<>(List.of("pwned"), callerA), Void.class);
        assertEquals(HttpStatus.NOT_FOUND, crossTag.getStatusCode(),
                "PUT tags on org B's memory as org A must 404");
        assertEquals(List.of("old-b"), memoryRepository.findById(memB).orElseThrow().getTopics(),
                "org B's topics must be unchanged after the blocked cross-org write");

        // 2. Own-org WRITE works → 204, row updated.
        ResponseEntity<Void> ownTag = rest.exchange(
                url("/api/memories/" + memA + "/tags"), HttpMethod.PUT,
                new HttpEntity<>(List.of("new-a"), callerA), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, ownTag.getStatusCode());
        assertEquals(List.of("new-a"), memoryRepository.findById(memA).orElseThrow().getTopics());

        // 3. Cross-org timeline READ leaks nothing.
        ResponseEntity<List<Map<String, Object>>> crossTimeline = rest.exchange(
                url("/api/memories/timeline/" + userB), HttpMethod.GET, new HttpEntity<>(callerA), JSON_LIST);
        assertEquals(HttpStatus.OK, crossTimeline.getStatusCode());
        assertTrue(crossTimeline.getBody().isEmpty(),
                "org A must not see org B's memory timeline");

        // 4. Cross-org export READ leaks nothing.
        ResponseEntity<List<Map<String, Object>>> crossExport = rest.exchange(
                url("/api/memories/export/" + userB), HttpMethod.GET, new HttpEntity<>(callerA), JSON_LIST);
        assertEquals(HttpStatus.OK, crossExport.getStatusCode());
        assertTrue(crossExport.getBody().isEmpty(),
                "org A must not export org B's memories");

        // 5. Own-org timeline READ still works.
        ResponseEntity<List<Map<String, Object>>> ownTimeline = rest.exchange(
                url("/api/memories/timeline/" + userA), HttpMethod.GET, new HttpEntity<>(callerA), JSON_LIST);
        assertEquals(HttpStatus.OK, ownTimeline.getStatusCode());
        assertEquals(1, ownTimeline.getBody().size(), "org A sees its own memory");
        assertEquals(memA.toString(), String.valueOf(ownTimeline.getBody().get(0).get("id")));
    }

    private UUID seedMemory(String userId, String orgId, String memory, List<String> topics) {
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
