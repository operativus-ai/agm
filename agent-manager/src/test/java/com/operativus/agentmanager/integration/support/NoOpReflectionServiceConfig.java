package com.operativus.agentmanager.integration.support;

import com.operativus.agentmanager.compute.service.AgentModelResolverService;
import com.operativus.agentmanager.compute.service.ReflectionService;
import com.operativus.agentmanager.control.repository.AgentReflectionRepository;
import com.operativus.agentmanager.control.service.MemoryService;
import com.operativus.agentmanager.control.service.SettingsService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Domain Responsibility: Replaces the production {@link ReflectionService} with a no-op
 *   subclass for integration tests that assert on ChatModel prompt counts or scripted
 *   {@link FakeChatModel} response ordering. The production bean runs {@code reflectOnRun}
 *   asynchronously (@Async, SimpleAsyncTaskExecutor) AFTER every successful run, and that
 *   background call fires one EXTRA upstream {@code chatClient.prompt().call()} against the
 *   fake model — racing with the next primary run, consuming scripted responses out of order,
 *   and also triggering {@code MemoryService.addMemory} → Vertex AI embeddings (which 403s
 *   loudly with {@code test-project} but succeeds enough to pollute the {@code agentic_memory}
 *   table). Disabling it here keeps sync/stream run tests deterministic.
 * State: Stateless (the @Primary override wins autowire across all test classes that import this config).
 *
 * Why a subclass instead of @MockitoBean: the real {@link ReflectionService} constructor
 *   eagerly resolves a "fast routing model" via {@link AgentModelResolverService}, which we
 *   still want exercised at bean-creation time so provider-initialization errors surface in
 *   the normal boot path. Subclassing preserves that and replaces only the hot-path method.
 *
 * Import alongside {@link FakeChatModelConfig} whenever the test scripts ChatModel responses
 *   and needs one fake call per one production call.
 */
@TestConfiguration
public class NoOpReflectionServiceConfig {

    @Bean
    @Primary
    public ReflectionService noOpReflectionService(
            AgentModelResolverService modelResolver,
            MemoryService memoryService,
            SettingsService settingsService,
            AgentReflectionRepository reflectionRepository) {
        return new ReflectionService(modelResolver, memoryService, settingsService, reflectionRepository) {
            @Override
            public void reflectOnRun(String userInput, String agentOutput, String userId,
                                     String runId, String agentId, String sessionId) {
                // no-op: tests assert on prompt counts and FakeChatModel FIFO ordering
            }
        };
    }
}
