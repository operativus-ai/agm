package ai.operativus.agentmanager;

import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.NoOpCacheConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// @SpringBootTest without extending BaseIntegrationTest — no Testcontainers,
// so it tries localhost:5432 and fails on CI. Has real assertions (2) —
// migrate to extend BaseIntegrationTest to re-enable under CI.
@Disabled("Requires host Postgres; not CI-compatible. Migrate to BaseIntegrationTest to re-enable under CI.")
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, NoOpCacheConfig.class})
public class AgentPersistenceTest {

    @org.springframework.beans.factory.annotation.Autowired
    private AgentRepository agentRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void testToolsPersistenceUpdate() {
        // Step 1: CREATE new Agent with EMPTY tools 
        AgentEntity entity = new AgentEntity();
        entity.setId("test-update-agent");
        entity.setName("Test Update");
        entity.setTools(new java.util.ArrayList<>());

        agentRepository.saveAndFlush(entity);
        entityManager.clear(); // EVIC L1 CACHE

        // Step 2: UPDATE existing Agent to ADD tools
        AgentEntity updateEntity = agentRepository.findById("test-update-agent").orElseThrow();
        updateEntity.setTools(new java.util.ArrayList<>(List.of("search_knowledge_base", "delegate_to_agent")));
        
        agentRepository.saveAndFlush(updateEntity);
        entityManager.clear(); // EVIC L1 CACHE

        // Step 3: VERIFY database updated
        AgentEntity fetched = agentRepository.findById("test-update-agent").orElseThrow();
        System.out.println("FETCHED UPDATED TOOLS: " + fetched.getTools());
        
        assertThat(fetched.getTools()).containsExactly("search_knowledge_base", "delegate_to_agent");
    }
}
