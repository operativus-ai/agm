package ai.operativus.agentmanager.core.registry;

import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.RunResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: In-memory mapping of active SSE Sinks. Bridges asynchronous Compute events to the WebFlux Control API.
 * State: Stateful (Thread-safe ConcurrentHashMap)
 */
@Component
public class InMemoryStreamRegistry implements StreamRegistry {

    private final Map<String, Sinks.Many<AgentStreamEvent>> activeSinks = new ConcurrentHashMap<>();
    private final Map<String, Sinks.One<RunResponse>> activeSyncs = new ConcurrentHashMap<>();

    @Override
    public Sinks.Many<AgentStreamEvent> registerSink(String runId) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        activeSinks.put(runId, sink);
        return sink;
    }

    @Override
    public void publishEvent(String runId, AgentStreamEvent event) {
        Sinks.Many<AgentStreamEvent> sink = activeSinks.get(runId);
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    @Override
    public Flux<AgentStreamEvent> getFlux(String runId) {
        Sinks.Many<AgentStreamEvent> sink = activeSinks.get(runId);
        if (sink != null) {
            return sink.asFlux()
                    .doFinally(signalType -> cleanup(runId));
        }
        return Flux.empty();
    }

    @Override
    public void complete(String runId) {
        Sinks.Many<AgentStreamEvent> sink = activeSinks.get(runId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
        cleanup(runId);
    }

    @Override
    public void error(String runId, Throwable t) {
        Sinks.Many<AgentStreamEvent> sink = activeSinks.get(runId);
        if (sink != null) {
            sink.tryEmitError(t);
        }
        cleanup(runId);
    }
    
    @Override
    public void registerSync(String runId, Sinks.One<RunResponse> sink) {
        activeSyncs.put(runId, sink);
    }

    @Override
    public void publishSyncResult(String runId, RunResponse response) {
        Sinks.One<RunResponse> sink = activeSyncs.get(runId);
        if (sink != null) {
            sink.tryEmitValue(response);
        }
    }

    @Override
    public Mono<RunResponse> getSync(String runId) {
        Sinks.One<RunResponse> sink = activeSyncs.get(runId);
        if (sink != null) {
            return sink.asMono()
                    .doFinally(signalType -> activeSyncs.remove(runId));
        }
        return Mono.empty();
    }

    private void cleanup(String runId) {
        activeSinks.remove(runId);
        activeSyncs.remove(runId);
    }
}
