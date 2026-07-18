package ai.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import ai.operativus.agentmanager.control.repository.KnowledgeContentRepository;
import ai.operativus.agentmanager.control.service.KnowledgeBaseService;
import ai.operativus.agentmanager.control.service.PersistentJobQueueService;
import ai.operativus.agentmanager.core.entity.KnowledgeBase;
import ai.operativus.agentmanager.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused mass-assignment coverage for
 * {@link KnowledgeBaseController#create(ai.operativus.agentmanager.control.dto.KnowledgeBaseRequest)}
 * and {@link KnowledgeBaseController#update(java.util.UUID, ai.operativus.agentmanager.control.dto.KnowledgeBaseRequest)}.
 *
 * <p>Threat shape: the entity has a public {@code setId(UUID)} setter and
 * {@code @GeneratedValue} only fires when the id is null on insert. With the
 * prior raw-entity binding ({@code @RequestBody KnowledgeBase}), a caller in
 * org A could POST {"id":"&lt;victim-kb-uuid&gt;", ...} and have Spring Data
 * {@code save()} merge → UPDATE the victim's row, rewriting name / description.
 * The orgId was already protected by {@code @JsonProperty(access = READ_ONLY)}
 * on the entity field, but the id was unprotected.
 *
 * <p>The DTO fix exposes only {@code name} and {@code description}; Jackson
 * drops any {@code id} / {@code orgId} / {@code createdAt} fields the body
 * declares.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllerMassAssignmentTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private KnowledgeBaseRepository repository;

    @Mock
    private KnowledgeContentRepository knowledgeContentRepository;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private PersistentJobQueueService jobQueueService;

    @InjectMocks
    private KnowledgeBaseController controller;

    private KnowledgeBase sampleKb;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        sampleKb = new KnowledgeBase();
        sampleKb.setId(UUID.randomUUID());
        sampleKb.setName("Procurement KB");
        sampleKb.setOrgId("DEFAULT_SYSTEM_ORG");
    }

    @Test
    void create_RejectsClientSuppliedId_AlwaysServerGenerated() throws Exception {
        when(repository.save(any())).thenReturn(sampleKb);

        UUID victimKbId = UUID.randomUUID();
        // Attacker payload: name + description (the legitimate fields) PLUS an id
        // pointing at another tenant's KB row.
        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", victimKbId.toString(),
                "name", "attacker-controlled",
                "description", "rewritten"));

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isOk());

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(repository).save(captor.capture());
        KnowledgeBase passed = captor.getValue();

        assertNull(passed.getId(),
                "attacker-supplied id must NOT reach the repository — id must be left "
                        + "null so Hibernate's @GeneratedValue assigns a fresh UUID. "
                        + "Without this guard, save() would merge on the attacker's id "
                        + "and overwrite the victim tenant's KB row.");
    }

    @Test
    void create_AttackerCannotMassAssignOrgId() throws Exception {
        when(repository.save(any())).thenReturn(sampleKb);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "name", "attacker-controlled",
                "description", "rewritten",
                "orgId", "VICTIM_ORG_ID"));

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isOk());

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(repository).save(captor.capture());
        assertNotEquals("VICTIM_ORG_ID", captor.getValue().getOrgId(),
                "attacker-supplied orgId must NOT reach the entity — orgId is "
                        + "server-derived from AgentContextHolder. AgentContextHolder "
                        + "is unset in this standalone MockMvc context so the controller "
                        + "falls back to DEFAULT_SYSTEM_ORG; either way it must NOT be "
                        + "the attacker's claimed org id.");
    }

    @Test
    void create_AttackerCannotMassAssignCreatedAt() throws Exception {
        when(repository.save(any())).thenReturn(sampleKb);

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "name", "attacker-controlled",
                "createdAt", "1970-01-01T00:00:00"));

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isOk());

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getCreatedAt(),
                "attacker-supplied createdAt must NOT reach the entity — Spring Data "
                        + "@CreatedDate via AuditingEntityListener owns this field. "
                        + "Allowing client override would let an attacker back-date "
                        + "knowledge bases to obscure audit trails.");
    }

    @Test
    void create_ValidationFailsOnBlankName() throws Exception {
        String invalidBody = objectMapper.writeValueAsString(Map.of(
                "description", "no name"));

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verify(repository, never()).save(any());
    }

    @Test
    void update_AttackerCannotMassAssignId_OnlyPathVariableUsed() throws Exception {
        UUID pathId = UUID.randomUUID();
        UUID victimAttempt = UUID.randomUUID();
        KnowledgeBase existing = new KnowledgeBase();
        existing.setId(pathId);
        existing.setOrgId("DEFAULT_SYSTEM_ORG");
        existing.setName("original");
        existing.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        when(repository.findByIdAndOrgId(pathId, "DEFAULT_SYSTEM_ORG"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String maliciousBody = objectMapper.writeValueAsString(Map.of(
                "id", victimAttempt.toString(),
                "name", "renamed-by-attacker",
                "orgId", "VICTIM_ORG_ID",
                "createdAt", "1970-01-01T00:00:00"));

        mockMvc.perform(put("/api/v1/knowledge-bases/" + pathId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousBody))
                .andExpect(status().isOk());

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(repository).save(captor.capture());
        KnowledgeBase saved = captor.getValue();
        // The entity that gets saved is the loaded `existing` — its id, orgId,
        // createdAt are whatever they were before. The DTO only mutates name +
        // description on the loaded row. Attacker's id/orgId/createdAt never reach it.
        org.junit.jupiter.api.Assertions.assertEquals(pathId, saved.getId(),
                "saved row id must be the loaded row's id (path variable), not the attacker's");
        org.junit.jupiter.api.Assertions.assertEquals("DEFAULT_SYSTEM_ORG", saved.getOrgId(),
                "saved row orgId must be the loaded row's orgId, not the attacker's");
        org.junit.jupiter.api.Assertions.assertEquals(LocalDateTime.of(2025, 1, 1, 0, 0),
                saved.getCreatedAt(),
                "saved row createdAt must be the loaded row's createdAt, not the attacker's");
        org.junit.jupiter.api.Assertions.assertEquals("renamed-by-attacker", saved.getName(),
                "the legitimate update field (name) should pass through");
    }
}
