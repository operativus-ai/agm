package ai.operativus.agentmanager.config;

import ai.operativus.agentmanager.core.exception.ApprovalRequiredException;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Domain Responsibility: Override Spring AI's default {@link ToolExecutionExceptionProcessor}
 * so that {@link ApprovalRequiredException} thrown by a tool callback propagates up to the
 * caller of {@code chain.nextCall(...)} instead of being stringified and fed back to the LLM
 * as a tool result.
 *
 * <p>Without this bean, Spring AI's {@link DefaultToolExecutionExceptionProcessor} catches
 * every {@link RuntimeException} from a {@code ToolCallback}, calls
 * {@link Throwable#getMessage() getMessage()}, and returns that string to the model as the
 * tool's output. The model then continues thinking with a stringified error in its history,
 * and HITL never reaches {@code AgentService}'s catch site at line 327. The audit
 * (.claude/reports/audit-advisor-chain-2026-05-04.md F1) verified this against
 * {@code spring-ai-model 2.0.0-SNAPSHOT}: {@code DEFAULT_ALWAYS_THROW=false} and the default
 * {@code rethrownExceptions} list is empty.</p>
 *
 * <p>By adding {@code ApprovalRequiredException.class} to the rethrow list, the processor
 * unwraps the {@code ToolExecutionException} and rethrows the original
 * {@code ApprovalRequiredException}, which then matches {@code AgentService}'s catch and
 * resolves to {@code RunStatus.PAUSED} with the proper {@code requiredAction} metadata.
 * Other exceptions still get converted to tool-result strings (Spring AI default).</p>
 *
 * State: Stateless
 */
@Configuration
public class ToolCallingExceptionConfig {

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(false)
                .rethrowExceptions(List.of(ApprovalRequiredException.class))
                .build();
    }
}
