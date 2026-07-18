package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.ComplianceTier;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AgentEmbeddingServiceTest {

    @Mock private VectorStore vectorStore;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentRegistry agentRegistry;

    private AgentEmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new AgentEmbeddingService(vectorStore, agentRepository, agentRegistry);
    }

    /** Mirrors the production documentId(): a deterministic UUID over the (org, agent) key. */
    private static String expectedDocId(String orgId, String agentId) {
        return UUID.nameUUIDFromBytes(("routing:" + orgId + ":" + agentId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static AgentDefinition agent(String id, String description) {
        return new AgentDefinition(
                id, id, description, "instructions", "model-x",
                null, null, null, null,
                false, false, null, null,
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void embedMissing_emptyCandidates_returnsZero() {
        int n = service.embedMissing("ORG", List.of());
        assertEquals(0, n);
        verifyNoInteractions(vectorStore);
    }

    @Test
    void embedMissing_skipsAgentsAlreadyEmbedded() {
        // Pretend the probe returns a doc for "a-1" but not "a-2"
        Document existing = Document.builder()
                .id("routing:ORG:a-1")
                .text("desc 1")
                .metadata("orgId", "ORG")
                .metadata("agentId", "a-1")
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(existing));

        int n = service.embedMissing("ORG", List.of(agent("a-1", "desc 1"), agent("a-2", "desc 2")));

        assertEquals(1, n);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<Document> added = captor.getValue();
        assertEquals(1, added.size());
        assertEquals(expectedDocId("ORG", "a-2"), added.get(0).getId());
    }

    @Test
    void embedMissing_skipsAgentsWithBlankDescription() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        int n = service.embedMissing("ORG", List.of(agent("a-1", "  "), agent("a-2", "real desc")));

        assertEquals(1, n);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals(expectedDocId("ORG", "a-2"), captor.getValue().get(0).getId());
    }

    /**
     * Regression pin for the "UUID string too large" backfill failure: routing_vectors.id is a
     * uuid column, so every Document handed to vectorStore.add(...) MUST carry a valid UUID id.
     * The previous "routing:ORG:agent" format threw IllegalArgumentException inside PgVectorStore
     * (mocked here, so the old unit test passed while the live endpoint 400'd).
     */
    @Test
    void embedAll_documentIdsAreValidDeterministicUuids() {
        when(agentRegistry.findAll(false, "DEFAULT_SYSTEM_ORG"))
                .thenReturn(List.of(agent("a-1", "desc 1"), agent("a-2", "desc 2")));

        service.embedAll("DEFAULT_SYSTEM_ORG");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        for (Document d : captor.getValue()) {
            // Must not throw — this is exactly the call PgVectorStore makes on insert.
            UUID parsed = UUID.fromString(d.getId());
            assertEquals(d.getId(), parsed.toString());
        }
        // Deterministic + unique per (org, agent).
        assertEquals(expectedDocId("DEFAULT_SYSTEM_ORG", "a-1"), captor.getValue().get(0).getId());
        assertEquals(expectedDocId("DEFAULT_SYSTEM_ORG", "a-2"), captor.getValue().get(1).getId());
        assertNotEquals(captor.getValue().get(0).getId(), captor.getValue().get(1).getId());
    }

    @Test
    void embedAll_emptyRoster_returnsZeroSummary() {
        when(agentRegistry.findAll(false, "ORG")).thenReturn(List.of());

        AgentEmbeddingService.BackfillSummary s = service.embedAll("ORG");

        assertEquals(0, s.totalAgents());
        assertEquals(0, s.embedded());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void embedAll_overwritesAllActiveAgents() {
        when(agentRegistry.findAll(false, "ORG"))
                .thenReturn(List.of(agent("a-1", "desc 1"), agent("a-2", "desc 2"), agent("a-3", " ")));

        AgentEmbeddingService.BackfillSummary s = service.embedAll("ORG");

        // 3 agents in roster; 2 with non-blank description got embedded
        assertEquals(3, s.totalAgents());
        assertEquals(2, s.embedded());
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void embedAll_blankOrgId_returnsZeroAndDoesNotTouchVectorStore() {
        AgentEmbeddingService.BackfillSummary s = service.embedAll("  ");
        assertEquals(0, s.totalAgents());
        assertEquals(0, s.embedded());
        verifyNoInteractions(vectorStore, agentRegistry);
    }
}
