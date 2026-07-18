package com.operativus.agentmanager.config;

import com.operativus.agentmanager.core.exception.ApprovalRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain Responsibility: Pins the contract of {@link ToolCallingExceptionConfig}'s override of
 *   Spring AI's default {@link ToolExecutionExceptionProcessor}. The bean is the single
 *   touchpoint that prevents {@link ApprovalRequiredException} from being silently
 *   stringified into LLM tool-result history (audit advisor-chain F1) — without it, HITL
 *   pauses never reach {@code AgentService}'s catch site at line 327 and the UI never sees
 *   {@code RunStatus.PAUSED}.
 *
 * State: Stateless. Slim {@code @SpringBootTest} loading only
 *   {@link ToolCallingExceptionConfig} — no DB, no Composio, no full app. ~0.5s.
 *
 * <p>Pins three properties of the configured processor:</p>
 * <ol>
 *   <li>the bean is registered (Spring's bean-definition would otherwise fall back to
 *       Spring AI's {@code DefaultToolExecutionExceptionProcessor} with empty rethrow list);
 *   <li>{@link ApprovalRequiredException} wrapped in {@link ToolExecutionException} is
 *       unwrapped and rethrown — preserving the HITL signal with its {@code approvalId},
 *       {@code toolName}, and {@code toolArgs};
 *   <li>a plain {@link RuntimeException} is NOT rethrown — the processor returns a string
 *       to be fed back to the LLM as the tool result (the default Spring AI swallow path).
 * </ol>
 */
@SpringBootTest(classes = ToolCallingExceptionConfig.class)
class ToolCallingExceptionConfigRuntimeTest {

    @Autowired
    private ToolExecutionExceptionProcessor processor;

    @Test
    void processorBeanIsPresent_overridingSpringAiDefault() {
        assertThat(processor)
                .as("the @Bean must be registered — without it Spring AI's default processor swallows ApprovalRequiredException and HITL never surfaces")
                .isNotNull();
    }

    @Test
    void approvalRequiredException_isRethrown_preservingHitlMetadata() {
        ApprovalRequiredException original = new ApprovalRequiredException(
                "appr-1234", "delete_database", "{\"force\":true}");
        ToolDefinition def = ToolDefinition.builder()
                .name("delete_database")
                .description("destructive")
                .inputSchema("{}")
                .build();
        ToolExecutionException wrapped = new ToolExecutionException(def, original);

        assertThatThrownBy(() -> processor.process(wrapped))
                .as("ApprovalRequiredException MUST be unwrapped and rethrown — stringifying it back to the LLM would erase the PAUSED signal")
                .isInstanceOf(ApprovalRequiredException.class)
                .satisfies(t -> {
                    ApprovalRequiredException re = (ApprovalRequiredException) t;
                    assertThat(re.getApprovalId()).isEqualTo("appr-1234");
                    assertThat(re.getToolName()).isEqualTo("delete_database");
                    assertThat(re.getToolArgs()).isEqualTo("{\"force\":true}");
                });
    }

    @Test
    void genericRuntimeException_isNotRethrown_returnsStringForLlmFeedback() {
        IllegalStateException underlying = new IllegalStateException("downstream API blew up");
        ToolDefinition def = ToolDefinition.builder()
                .name("some_tool")
                .description("any")
                .inputSchema("{}")
                .build();
        ToolExecutionException wrapped = new ToolExecutionException(def, underlying);

        String result = processor.process(wrapped);

        assertThat(result)
                .as("non-rethrow-listed RuntimeException MUST be returned as a string for LLM tool-result feedback — Spring AI's default swallow path")
                .isNotNull();
    }
}
