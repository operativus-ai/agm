package ai.operativus.agentmanager.compute.evaluation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Domain Responsibility: Runs in parallel via Virtual Threads to evaluate primary model inferences.
 * Pushes Quality of Service (QoS) metrics into OpenTelemetry via Micrometer.
 * State: Stateless
 */
@Service
public class CriticAgentSidecar {

    private static final Logger log = LoggerFactory.getLogger(CriticAgentSidecar.class);
    private final MeterRegistry meterRegistry;
    
    // Dedicated executor simulating Virtual Thread sidecar offloading without blocking the main workflow 
    // Usually injected, but explicitly defined here for immediate Virtual Thead isolation 
    private final ExecutorService sidecarExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final org.springframework.ai.chat.client.ChatClient chatClient;

    public CriticAgentSidecar(MeterRegistry meterRegistry, org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder) {
        this.meterRegistry = meterRegistry;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Evaluates a completed inference payload for quality, hallucinations, and formatting.
     */
    public void evaluateInference(String agentId, String teamId, String prompt, String completionPayload) {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        // F19 — fresh VT does NOT inherit JDK 21 ScopedValues. Rebind AgentContextHolder bindings
        // so the chatClient advisor chain inside calculateQualityHeuristic sees the right tenant
        // (advisors read orgId/userId for RAG/PII/cultural memory).
        final ai.operativus.agentmanager.core.callback.AgentContextSnapshot snapshot =
                ai.operativus.agentmanager.core.callback.AgentContextSnapshot.capture();
        sidecarExecutor.submit(() -> {
            try {
                org.springframework.security.core.context.SecurityContextHolder.setContext(context);
                snapshot.run(() -> performEvaluation(agentId, teamId, prompt, completionPayload));
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        });
    }

    private void performEvaluation(String agentId, String teamId, String prompt, String completionPayload) {
        log.debug("Critic Agent assessing completion payload length: {} for Agent: {}", completionPayload.length(), agentId);

        double qualityScore = calculateQualityHeuristic(prompt, completionPayload);

        // Emit metric to OpenTelemetry/Micrometer
        meterRegistry.gauge("agent.inference.qos", 
            List.of(
                Tag.of("agent.id", agentId),
                Tag.of("team.id", teamId)
            ), 
            qualityScore);
            
        log.info("Critic Agent evaluation completed for {} with score {}", agentId, qualityScore);
    }

    private double calculateQualityHeuristic(String prompt, String payload) {
        if (payload == null || payload.isBlank()) return 0.0;
        try {
            String evaluationPrompt = "Evaluate the quality of the following completion as a score between 0.0 and 1.0. Just output the number. " +
                                      "Prompt: {prompt}\nCompletion: {payload}";
            String response = chatClient.prompt()
                    .user(u -> u.text(evaluationPrompt)
                            .param("prompt", prompt)
                            .param("payload", payload))
                    .call()
                    .content();
            
            return Double.parseDouble(response.trim());
        } catch (Exception e) {
            log.warn("Critic LLM evaluation failed, falling back to heuristic", e);
            return Math.min(1.0, payload.length() / 1000.0) * 0.8 + 0.2;
        }
    }
}
