package com.operativus.agentmanager.core.registry;

import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import java.util.List;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

/**
 * Domain Responsibility: Registry contract to access core Agent Execution operations from the Control plane.
 * State: Stateless
 */
public interface AgentOperations {

    /**
     * @summary Executes an agent run with minimal parameters.
     * @logic Resolves session and invokes the synchronous blocking run logic.
     */
    RunResponse run(String agentId, String userInput, String sessionId);

    /**
     * @summary Executes a parameterized agent run supporting media and organization tracking.
     * @logic Prepares complex context, media inputs, and calls underlying Language Models.
     */
    RunResponse run(String agentId, String userInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options);

    /**
     * @summary Starts a Server-Sent Events stream for agent execution tokens.
     * @logic Subscribes to the underlying AI model reactive stream and emits Token/Tool events.
     */
    Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options);

    /**
     * @summary Submits a non-blocking asynchronous agent task.
     * @logic Enqueues task onto a Virtual Thread and returns a tracking ID.
     */
    String runInBackground(String agentId, String userInput, List<Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options);

    /**
     * @summary Submits a minimal background task.
     * @logic Resolves default context and delegates to Virtual Thread execution.
     */
    String runInBackground(String agentId, String userInput, String sessionId);

    /**
     * @summary Executes a run specifically configured for the Playground environment.
     * @logic Overrides default prompt constraints for interactive debugging mode.
     */
    String runPlayground(String agentId, String userInput, String sessionId);

    /**
     * @summary Issues a cancellation signal for an active execution.
     * @logic Interrupts the backing Java 21 Virtual Thread executing the specified run ID.
     */
    void cancelRun(String runId);

    /**
     * @summary Resumes a Human-In-The-Loop paused execution.
     * @logic Injects user action and unblocks the execution thread polling.
     */
    RunResponse continueRun(String runId, String action);

    /**
     * @summary Triggers asynchronous ingestion of Agent's defined Knowledge sources.
     * @logic Invokes web scraping or document embedding into pgvector datastore.
     */
    void loadKnowledge(String agentId);
}
