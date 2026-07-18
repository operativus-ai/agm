package ai.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import ai.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import ai.operativus.agentmanager.core.entity.KnowledgeBase;
import ai.operativus.agentmanager.core.entity.KnowledgeContent;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class KnowledgeServiceTest {

    @Mock private VectorStore vectorStore;
    @Mock private KnowledgeContentRepository knowledgeRepo;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private SafetyService safetyService;
    @Mock private IngestionStatusService ingestionStatusService;

    private KnowledgeService service;
    private org.mockito.MockedStatic<ai.operativus.agentmanager.core.callback.AgentContextHolder> mockedContext;

    @BeforeEach
    void setUp() {
        mockedContext = mockStatic(ai.operativus.agentmanager.core.callback.AgentContextHolder.class);
        mockedContext.when(ai.operativus.agentmanager.core.callback.AgentContextHolder::getOrgId).thenReturn("TEST_ORG");
        mockedContext.when(ai.operativus.agentmanager.core.callback.AgentContextHolder::getAllowedKnowledgeBaseIds).thenReturn(java.util.Collections.emptyList());
        service = new KnowledgeService(vectorStore, knowledgeRepo, jdbcTemplate, knowledgeBaseRepository, objectMapper, safetyService, ingestionStatusService, false, 4, 0.4);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (mockedContext != null) {
            mockedContext.close();
        }
    }

    @Test
    void resolveCategoryId_ExistingCategory_ReturnsId() {
        UUID mockId = UUID.randomUUID();
        KnowledgeBase mockKb = mock(KnowledgeBase.class);
        when(mockKb.getId()).thenReturn(mockId);

        // resolveCategoryId now uses the tenant-scoped finder.
        when(knowledgeBaseRepository.findByNameAndOrgId("Existing Category", "TEST_ORG"))
                .thenReturn(Optional.of(mockKb));

        UUID result = service.resolveCategoryId("Existing Category", "http://any.url");
        assertEquals(mockId, result);
        verify(knowledgeBaseRepository, never()).save(any());
    }

    @Test
    void resolveCategoryId_NoCategoryWithFallback_CreatesNewAndReturnsId() {
        when(knowledgeBaseRepository.findByNameAndOrgId("Scraped example.com", "TEST_ORG"))
                .thenReturn(Optional.empty());

        UUID newId = UUID.randomUUID();
        KnowledgeBase newKb = mock(KnowledgeBase.class);
        when(newKb.getId()).thenReturn(newId);

        when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenReturn(newKb);

        UUID result = service.resolveCategoryId(null, "http://example.com/path");

        assertEquals(newId, result);
        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseRepository).save(captor.capture());
        assertEquals("Scraped example.com", captor.getValue().getName());
        // The new KB is stamped with the caller's orgId (TEST_ORG via the mocked AgentContextHolder).
        assertEquals("TEST_ORG", captor.getValue().getOrgId(),
                "new KB must carry caller's orgId so cross-tenant scraping doesn't leak rows");
    }

    @Test
    void ingestText_DuplicateUri_ThrowsBusinessValidation() {
        when(knowledgeRepo.existsByUri("http://duplicate.com")).thenReturn(true);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> 
            service.ingestText("Title", "Content here", "http://duplicate.com", UUID.randomUUID())
        );
        assertTrue(ex.getMessage().contains("URI already exists"));
    }

    @Test
    void ingestText_ValidSetup_SavesChunksAndUpdatesMetadata() {
        String url = "http://valid.com";
        UUID kbId = UUID.randomUUID();
        
        when(knowledgeRepo.existsByUri(url)).thenReturn(false);

        KnowledgeContent mockContent = mock(KnowledgeContent.class);
        UUID contentId = UUID.randomUUID();
        when(mockContent.getId()).thenReturn(contentId);
        when(knowledgeRepo.save(any(KnowledgeContent.class))).thenReturn(mockContent);
        
        when(knowledgeRepo.findById(contentId)).thenReturn(Optional.of(mockContent));

        // ingestText sanitizes each chunk via sanitizeForStorage (not sanitizeInput). Spring AI
        // 2.0.0-SNAPSHOT's Document now rejects null/blank text ("exactly one of text or media"),
        // so the passthrough stub is required (an unstubbed null would blow up Document construction).
        when(safetyService.sanitizeForStorage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        // Use short text to avoid extreme chunking counts
        service.ingestText("Test Title", "Sample Markdown Body \n\n## Header\nMore text", url, kbId);

        // Verify save of chunks
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(docsCaptor.capture());
        
        List<Document> savedDocs = docsCaptor.getValue();
        assertFalse(savedDocs.isEmpty(), "Documents should be saved into VectorDB");

        // Verify final save updates status back to COMPLETED
        verify(mockContent).setStatus(ai.operativus.agentmanager.core.model.enums.RunStatus.COMPLETED);
        verify(knowledgeRepo, times(2)).save(any(KnowledgeContent.class));
    }

    @Test
    void ingestText_chunkSanitizesToBlank_skipsChunkInsteadOfFailing() {
        String url = "http://blank.com";
        UUID kbId = UUID.randomUUID();
        when(knowledgeRepo.existsByUri(url)).thenReturn(false);

        KnowledgeContent mockContent = mock(KnowledgeContent.class);
        UUID contentId = UUID.randomUUID();
        when(mockContent.getId()).thenReturn(contentId);
        when(knowledgeRepo.save(any(KnowledgeContent.class))).thenReturn(mockContent);
        when(knowledgeRepo.findById(contentId)).thenReturn(Optional.of(mockContent));
        // Every chunk fully redacts to blank — must not crash under Spring AI 2.0.0-SNAPSHOT's
        // stricter Document (rejects null/blank text). Pre-guard this threw "exactly one of text
        // or media must be specified" and failed the entire ingestion; now blank chunks are skipped.
        when(safetyService.sanitizeForStorage(anyString())).thenReturn("   ");

        assertDoesNotThrow(() ->
                service.ingestText("Title", "Some body text\n\n## Header\nmore text", url, kbId));

        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(docsCaptor.capture());
        assertTrue(docsCaptor.getValue().isEmpty(), "all blank-sanitized chunks must be skipped");
    }

    @Test
    void search_ValidatesVectorStoreDelegation() {
        Document mockDoc = mock(Document.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(mockDoc));

        List<Document> results = service.search("keyword");

        assertEquals(1, results.size());
        // Pin the configurable retrieval knobs onto the outbound SearchRequest (default 4 / 0.4).
        ArgumentCaptor<org.springframework.ai.vectorstore.SearchRequest> reqCaptor =
                ArgumentCaptor.forClass(org.springframework.ai.vectorstore.SearchRequest.class);
        verify(vectorStore).similaritySearch(reqCaptor.capture());
        assertEquals(4, reqCaptor.getValue().getTopK());
        assertEquals(0.4, reqCaptor.getValue().getSimilarityThreshold());
        assertNotNull(reqCaptor.getValue().getFilterExpression(),
                "search must carry the tenant-scoping filter");
    }

    @Test
    void searchWithExplicitTopK_overridesDefaultPoolSize() {
        Document mockDoc = mock(Document.class);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(mockDoc));

        // Phase 2: the RAG advisor pulls a larger candidate pool via this overload — the passed
        // topK (not the default 4) must flow to the outbound SearchRequest.
        service.search("keyword", 20);

        ArgumentCaptor<org.springframework.ai.vectorstore.SearchRequest> reqCaptor =
                ArgumentCaptor.forClass(org.springframework.ai.vectorstore.SearchRequest.class);
        verify(vectorStore).similaritySearch(reqCaptor.capture());
        assertEquals(20, reqCaptor.getValue().getTopK());
        assertEquals(0.4, reqCaptor.getValue().getSimilarityThreshold());
    }

    @Test
    void keywordSearch_ExecutesJdbcQuery() {
        Document doc = mock(Document.class);
        
        // Suppress mockito warnings on raw query
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
            .thenReturn((List) List.of(doc));

        List<Document> results = service.keywordSearch("test query", 10);
        assertEquals(1, results.size());
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq("test query"), eq("TEST_ORG"), eq(10));
    }
}
