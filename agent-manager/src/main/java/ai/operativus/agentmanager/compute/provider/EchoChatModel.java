package ai.operativus.agentmanager.compute.provider;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Domain Responsibility: A keyless, deterministic {@link ChatModel} that echoes the caller's
 * last user message back as the assistant response. It makes NO network calls and needs NO
 * provider credential — its sole purpose is to let demo workflows (and any offline scenario)
 * run end-to-end and produce observable, predictable per-step output without an LLM API key.
 *
 * <p>Wired into the model-resolution path via {@link EchoModelProvider} (provider key
 * {@code "ECHO"}). Any {@code models} row with {@code provider='ECHO'} resolves to this model;
 * see {@code AgentClientFactory.instantiateCustomChatModel}.</p>
 *
 * State: Stateless and thread-safe (the label is immutable; no scripted queue).
 */
public final class EchoChatModel implements ChatModel {

    /** Short label (the model name) prefixed onto each echo so the source step is visible. */
    private final String label;

    public EchoChatModel(String label) {
        this.label = (label == null || label.isBlank()) ? "echo" : label;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = lastUserText(prompt);
        String reply = "[" + currentLabel() + "] " + userText;
        int tokens = Math.max(1, userText.length() / 4); // rough, just so usage is non-zero
        return response(reply, tokens);
    }

    /**
     * The label for THIS call. A single {@link EchoChatModel} instance is cached per model id
     * and shared across every agent bound to that model, so the constructor label can't identify
     * the calling agent. The agent name is, however, bound per-run in
     * {@link ai.operativus.agentmanager.core.callback.AgentContextHolder} (and mirrored to MDC),
     * so we read it here to attribute each echo to the agent that produced it.
     */
    private String currentLabel() {
        String name = ai.operativus.agentmanager.core.callback.AgentContextHolder.getAgentName();
        if (name == null || name.isBlank()) {
            name = org.slf4j.MDC.get("agentName");
        }
        return (name == null || name.isBlank()) ? label : name;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Single terminal chunk — enough for streaming surfaces to render the echo.
        return Flux.just(call(prompt));
    }

    /** Last USER message's text, or the last message's text if none is typed USER. */
    private static String lastUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        if (messages == null || messages.isEmpty()) {
            return "(no input)";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getMessageType() == MessageType.USER) {
                String t = m.getText();
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        String last = messages.get(messages.size() - 1).getText();
        return (last == null || last.isBlank()) ? "(no input)" : last;
    }

    private static ChatResponse response(String text, int tokens) {
        ChatGenerationMetadata genMeta = ChatGenerationMetadata.builder().finishReason("STOP").build();
        Generation gen = new Generation(new AssistantMessage(text), genMeta);
        DefaultUsage usage = new DefaultUsage(tokens, tokens, tokens * 2);
        ChatResponseMetadata meta = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(gen), meta);
    }
}
