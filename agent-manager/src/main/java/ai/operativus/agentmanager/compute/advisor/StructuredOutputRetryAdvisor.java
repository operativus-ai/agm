package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.core.model.MetricConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
/**
 * Domain Responsibility: Intercepts ChatClient calls for agents that enforce structured JSON output.
 * If the LLM produces malformed JSON that fails downstream parsing, this advisor catches the
 * exception and enters a bounded reflection loop — re-prompting the LLM with the exact parse
 * error to allow self-correction.
 *
 * State: Stateless (Advisor)
 *
 * @architecture This advisor is conditionally bound only to agents with {@code enforceJsonOutput=true}
 *               in their definition. It wraps the call chain and handles retry logic internally,
 *               maintaining a strict {@code maxRetries} ceiling to prevent infinite loops and
 *               protect FinOps token budgets.
 */
public class StructuredOutputRetryAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputRetryAdvisor.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final String REFLECTION_INSTRUCTION =
            "Your previous response contained invalid JSON that could not be parsed. " +
            "You MUST correct your output. Return ONLY a valid, bare JSON object with no markdown " +
            "formatting (no ```json blocks), no explanatory text, and no trailing content. " +
            "The exact parsing error was:\n\n";

    private final int maxRetries;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer, tag {@code advisor=structured_output_retry}. */
    private final Timer durationTimer;

    public StructuredOutputRetryAdvisor(int maxRetries, MeterRegistry meterRegistry) {
        this.maxRetries = maxRetries;
        this.durationTimer = Timer.builder(MetricConstants.ADVISOR_DURATION_MS)
                .tag("advisor", "structured_output_retry").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "StructuredOutputRetryAdvisor";
    }

    /**
     * @summary Runs early in the chain to wrap the entire call with JSON validation retry logic.
     */
    @Override
    public int getOrder() {
        return 1;
    }

    /**
     * @summary Intercepts ChatClient calls, validates JSON output, and triggers reflection retries on failure.
     * @logic
     * 1. Delegates the original request through the advisor chain normally.
     * 2. Extracts the raw content string from the response.
     * 3. Attempts to parse the content as JSON using a lightweight structural validation.
     * 4. If parsing fails, constructs a new request appending the parse error as user context,
     *    then re-invokes the chain — up to {@code maxRetries} attempts.
     * 5. If all retries exhaust, returns the last response as-is (degraded but non-fatal).
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            ChatClientResponse response = chain.nextCall(request);
            String content = extractContent(response);

            if (content == null || content.isBlank()) {
                return response;
            }

            String validationError = validateJson(content);
            if (validationError == null) {
                return response; // Valid JSON — pass through
            }

            log.warn("StructuredOutputRetryAdvisor: Initial output failed JSON validation. Entering reflection loop. Error: {}", validationError);

            ChatClientResponse lastResponse = response;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                log.info("StructuredOutputRetryAdvisor: Reflection attempt {}/{}", attempt, maxRetries);

                ChatClientRequest retryRequest = buildReflectionRequest(request, content, validationError);
                lastResponse = chain.nextCall(retryRequest);

                String retryContent = extractContent(lastResponse);
                if (retryContent == null || retryContent.isBlank()) {
                    log.warn("StructuredOutputRetryAdvisor: Retry {} returned empty content.", attempt);
                    continue;
                }

                String retryError = validateJson(retryContent);
                if (retryError == null) {
                    log.info("StructuredOutputRetryAdvisor: Retry {} succeeded. Valid JSON produced.", attempt);
                    return lastResponse;
                }

                log.warn("StructuredOutputRetryAdvisor: Retry {} still invalid. Error: {}", attempt, retryError);
                content = retryContent;
                validationError = retryError;
            }

            log.error("StructuredOutputRetryAdvisor: All {} retries exhausted. Returning last response as-is.", maxRetries);
            return lastResponse;
        });
    }

    /**
     * @summary Extracts the raw text content from a ChatClientResponse.
     */
    private String extractContent(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return null;
        }
        var result = response.chatResponse().getResult();
        if (result == null || result.getOutput() == null) {
            return null;
        }
        return result.getOutput().getText();
    }

    /**
     * @summary Performs lightweight structural JSON validation without full deserialization.
     * @logic Strips markdown code fences, then checks that the content starts with '{' or '['
     *        and can be parsed by Jackson's ObjectMapper. Returns null on success, or the error
     *        message string on failure.
     */
    private String validateJson(String content) {
        String stripped = content.strip();

        // Strip markdown code fences that LLMs love to add
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            if (firstNewline > 0) {
                stripped = stripped.substring(firstNewline + 1);
            }
            if (stripped.endsWith("```")) {
                stripped = stripped.substring(0, stripped.length() - 3);
            }
            stripped = stripped.strip();
        }

        if (stripped.isEmpty()) {
            return "Response was empty after stripping markdown fences.";
        }

        char first = stripped.charAt(0);
        if (first != '{' && first != '[') {
            return "Response does not start with '{' or '['. First character: '" + first + "'. " +
                   "Content preview: " + stripped.substring(0, Math.min(100, stripped.length()));
        }

        try {
            OBJECT_MAPPER.readTree(stripped);
            return null; // Success
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return e.getOriginalMessage();
        }
    }

    /**
     * @summary Constructs a reflection request appending the parse error for LLM self-correction.
     * @logic Preserves the original system message and conversation history. Appends the LLM's
     *        failed output and the exact parse error as a new user message instructing correction.
     */
    private ChatClientRequest buildReflectionRequest(ChatClientRequest originalRequest,
                                                      String failedContent,
                                                      String parseError) {
        List<Message> messages = new ArrayList<>();

        // Preserve original messages
        if (originalRequest.prompt() != null && originalRequest.prompt().getInstructions() != null) {
            messages.addAll(originalRequest.prompt().getInstructions());
        }

        // Append the failed output and correction instruction
        String reflectionMessage = "Your previous output was:\n\n" + failedContent + "\n\n" +
                REFLECTION_INSTRUCTION + parseError;
        messages.add(new UserMessage(reflectionMessage));

        Prompt reflectionPrompt = new Prompt(
                messages,
                originalRequest.prompt() != null ? originalRequest.prompt().getOptions() : null
        );

        return originalRequest.mutate()
                .prompt(reflectionPrompt)
                .build();
    }
}
