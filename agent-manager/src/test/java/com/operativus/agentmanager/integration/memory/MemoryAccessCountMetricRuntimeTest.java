package com.operativus.agentmanager.integration.memory;

import com.operativus.agentmanager.control.service.MemoryService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetricConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin that {@code MemoryService.searchMemories} and
 *   {@code MemoryService.searchUserMemories} each increment the
 *   {@code agm.memory.search.total} Micrometer counter with the correct
 *   {@code source} tag ({@code memories} vs {@code user_memories}).
 *   Guards against advisor-chain regressions that silently disable memory
 *   retrieval — a flat counter rate in Grafana surfaces immediately.
 * State: Stateless (per-test isolation via truncate + ScopedValue orgId binding).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
class MemoryAccessCountMetricRuntimeTest extends BaseIntegrationTest {

    @Autowired private MemoryService memoryService;
    @Autowired private MeterRegistry meterRegistry;

    private static final String ORG = "metric-test-org";

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM agentic_memory_outbox");
        jdbc.update("DELETE FROM agentic_memories");
        jdbc.update("DELETE FROM vector_store");
    }

    @Test
    void searchMemories_incrementsCounter_sourceTagMemories() throws Exception {
        ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(() -> {
                    memoryService.searchMemories("test query");
                    return null;
                });

        Counter counter = meterRegistry
                .find(MetricConstants.MEMORY_SEARCH_TOTAL)
                .tag("source", "memories")
                .counter();

        assertNotNull(counter,
                "agm.memory.search.total{source=memories} must be registered after searchMemories executes");
        assertTrue(counter.count() >= 1.0,
                "agm.memory.search.total{source=memories} must increment at least once; got " + counter.count());
    }

    @Test
    void searchUserMemories_incrementsCounter_sourceTagUserMemories() throws Exception {
        ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(() -> {
                    memoryService.searchUserMemories("some-user");
                    return null;
                });

        Counter counter = meterRegistry
                .find(MetricConstants.MEMORY_SEARCH_TOTAL)
                .tag("source", "user_memories")
                .counter();

        assertNotNull(counter,
                "agm.memory.search.total{source=user_memories} must be registered after searchUserMemories executes");
        assertTrue(counter.count() >= 1.0,
                "agm.memory.search.total{source=user_memories} must increment at least once; got " + counter.count());
    }
}
