package ai.operativus.agentmanager.control.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Manages system telemetry and metrics tracking using Micrometer.
 * State: Stateless
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final MeterRegistry meterRegistry;
    private final Counter agentRunsCounter;
    private final Counter toolCallsCounter;
    private final Counter errorCounter;

    public TelemetryService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        this.agentRunsCounter = Counter.builder("agent.runs.total")
                .description("Total number of agent runs executed")
                .register(meterRegistry);
        
        this.toolCallsCounter = Counter.builder("agent.tools.calls.total")
                .description("Total number of tool calls executed")
                .register(meterRegistry);

        this.errorCounter = Counter.builder("agent.errors.total")
                .description("Total number of execution errors")
                .register(meterRegistry);
    }

    /**
     * @summary Increments the total count of executed agent runs.
     * @logic Logs a trace message for telemetry increment, increments the global agent runs counter, and increments the agent-specific runs counter via MeterRegistry.
     */
    public void incrementAgentRuns(String agentId) {
        log.trace("Incrementing telemetry: agent runs for agent ID: {}", agentId);
        agentRunsCounter.increment();
        meterRegistry.counter("agent.runs", "agent_id", agentId).increment();
    }

    /**
     * @summary Increments the total count of executed tool calls.
     * @logic Logs a trace message for tool call increment, increments the global tool calls counter, and increments the tool-specific calls counter via MeterRegistry.
     */
    public void incrementToolCalls(String toolName) {
        log.trace("Incrementing telemetry: tool calls for tool: {}", toolName);
        toolCallsCounter.increment();
        meterRegistry.counter("agent.tools.calls", "tool_name", toolName).increment();
    }
    
    /**
     * @summary Increments the total count of execution errors by type.
     * @logic Logs a trace message for error increment, increments the global error counter, and increments the specific error type counter via MeterRegistry.
     */
    public void incrementErrors(String errorType) {
        log.trace("Incrementing telemetry: errors of type: {}", errorType);
        errorCounter.increment();
        meterRegistry.counter("agent.errors", "type", errorType).increment();
    }

    /**
     * @summary Records the time duration taken for an agent run execution.
     * @logic Logs a trace message for run duration, and records the duration in milliseconds tagging the specific agent ID.
     */
    public void recordRunDuration(String agentId, Duration duration) {
        log.trace("Recording telemetry: run duration for agent ID {} is {} ms", agentId, duration.toMillis());
        Timer.builder("agent.run.duration")
                .tag("agent_id", agentId)
                .register(meterRegistry)
                .record(duration);
    }
}
