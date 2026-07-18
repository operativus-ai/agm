package com.operativus.agentmanager;

import com.operativus.agentmanager.control.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

// Exploratory @SpringBootTest (0 assertions). Requires host Postgres.
// Not CI-compatible. Re-enable locally by removing @Disabled.
@Disabled("Exploratory scratch; requires host Postgres. Not CI-compatible.")
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Tag("integration")
public class KbTest {

    private final KnowledgeBaseRepository repository;

    public KbTest(KnowledgeBaseRepository repository) {
        this.repository = repository;
    }

    @Test
    public void testSaving() throws Exception {
        System.out.println("TEST_START: Saving KnowledgeBase");
        com.operativus.agentmanager.core.entity.KnowledgeBase kb = new com.operativus.agentmanager.core.entity.KnowledgeBase("spring boot docs " + java.util.UUID.randomUUID(), "auto gen");
        kb = repository.save(kb);
        System.out.println("TEST_SUCCESS: Saved KnowledgeBase with ID " + kb.getId());
    }
}
