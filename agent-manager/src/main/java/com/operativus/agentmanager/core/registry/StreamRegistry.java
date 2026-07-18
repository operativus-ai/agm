package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.RunResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Mono;

/**
 * Domain Responsibility: Defines the contract for passing asynchronous LLM stream tokens between the Compute Plane and the Control Plane API.
 * State: Stateless Interface
 */
public interface StreamRegistry {
    
    /**
     * Registers a new Sink for a given Run ID.
     */
    Sinks.Many<AgentStreamEvent> registerSink(String runId);

    /**
     * Publishes an event to the specific Run ID's Sink.
     */
    void publishEvent(String runId, AgentStreamEvent event);

    /**
     * Retrieves the Flux representation of the Sink for HTTP streaming.
     */
    Flux<AgentStreamEvent> getFlux(String runId);

    /**
     * Flags a stream as complete, notifying subscribers and cleaning up memory.
     */
    void complete(String runId);

    /**
     * Fails a stream with a standard architectural exception.
     */
    void error(String runId, Throwable t);

    // Synchronous Over-Event Bridge Helpers
    void registerSync(String runId, Sinks.One<RunResponse> sink);
    void publishSyncResult(String runId, RunResponse response);
    Mono<RunResponse> getSync(String runId);
}
