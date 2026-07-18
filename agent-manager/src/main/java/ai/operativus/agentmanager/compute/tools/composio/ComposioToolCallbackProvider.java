package ai.operativus.agentmanager.compute.tools.composio;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.ComposioConnectionConfigRepository;
import ai.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Domain Responsibility: Spring AI {@link ToolCallbackProvider} that registers one
 * {@link ComposioToolCallback} per enabled Composio action. Rebuilt automatically when
 * {@link ComposioActionRegistry} reloads after a {@link ComposioConfigChangedEvent}, so action
 * additions and removals take effect without restart. If the Composio API key is blank,
 * registers ZERO callbacks and logs once at INFO. Per spec §3 Error-Handling-credential-missing AC.
 *
 * <p><strong>No direct event listener.</strong> The application multicaster is async (one
 * vthread per listener — see {@code AgentManagerConfig.applicationEventMulticaster}); two
 * listeners on the same event race. This provider therefore subscribes to the registry's
 * synchronous {@code addReloadListener} hook, which fires AFTER the registry's own state swap.
 * Removing the direct {@code @EventListener} closed the listener-order race that
 * {@code ComposioHotReloadCompositeRuntimeTest} previously masked with an Awaitility
 * re-publish loop.</p>
 *
 * <p>The downstream merge into Spring AI's global tool roster is handled by
 * {@code compute/config/ToolConfig.globalToolProvider}, which consumes this provider's
 * callbacks alongside the {@code @AgentToolComponent}-derived ones.</p>
 *
 * <p>Per audit Findings 3, 4, 7, 11, this provider relies on:
 * {@link ComposioActionRegistry} for the enabled-action list (with cap+warn enforcement),
 * the singleton {@code composioWebClient} bean, the singleton {@code composioCircuitBreaker}
 * bean shared across all callbacks, and the project's {@link ObjectMapper} for serialization.</p>
 */
@Component
public class ComposioToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(ComposioToolCallbackProvider.class);

    private final ComposioActionRegistry registry;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final String apiKey;
    private final Environment environment;
    private final ComposioConnectionConfigRepository connectionRepository;

    private final AtomicReference<List<ToolCallback>> callbacks;

    public ComposioToolCallbackProvider(
            ComposioActionRegistry registry,
            @Qualifier("composioWebClient") WebClient webClient,
            ObjectMapper objectMapper,
            @Qualifier("composioCircuitBreaker") CircuitBreaker circuitBreaker,
            @Value("${agent.tools.composio.api-key:}") String apiKey,
            Environment environment,
            ComposioConnectionConfigRepository connectionRepository) {
        this.registry = registry;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.apiKey = apiKey;
        this.environment = environment;
        this.connectionRepository = connectionRepository;
        this.callbacks = new AtomicReference<>(buildCallbacks());
        // Subscribe to the registry's synchronous reload callback rather than to the
        // ComposioConfigChangedEvent directly. The application multicaster dispatches each
        // @EventListener on its own virtual thread, so two listeners on the same event race;
        // before this seam existed, the provider's getEnabledActions() call could observe the
        // pre-swap registry snapshot and rebuild stale callbacks. The registry now invokes
        // this Runnable AFTER its state swap, so the rebuild always sees the fresh action set.
        registry.addReloadListener(this::onRegistryReloaded);
    }

    private void onRegistryReloaded() {
        log.debug("ComposioToolCallbackProvider rebuilding callbacks after registry reload");
        try {
            List<ToolCallback> updated = buildCallbacks();
            callbacks.set(updated);
            log.info("ComposioToolCallbackProvider rebuilt {} tool callbacks after registry reload.", updated.size());
        } catch (RuntimeException e) {
            log.warn("ComposioToolCallbackProvider failed to rebuild callbacks — retaining prior snapshot", e);
        }
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.get().toArray(new ToolCallback[0]);
    }

    private List<ToolCallback> buildCallbacks() {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("Composio adapter disabled: agent.tools.composio.api-key (COMPOSIO_API_KEY) is unset. Zero tool callbacks registered.");
            return Collections.emptyList();
        }

        if (registry.getEnabledCount() == 0) {
            log.info("Composio adapter API key present but agent.tools.composio.enabled-actions is empty. Zero tool callbacks registered.");
            return Collections.emptyList();
        }

        List<ToolCallback> built = new ArrayList<>();
        for (String actionName : registry.getEnabledActions()) {
            String description = "Invoke the Composio action " + actionName
                    + ". Refer to Composio's action catalogue for the input schema; the LLM is expected to construct"
                    + " a JSON object matching that schema. Returns the action result wrapped in a Composio response envelope.";
            built.add(new ComposioToolCallback(actionName, description, webClient, objectMapper,
                    circuitBreaker, apiKey, environment, connectionRepository));
        }
        List<ToolCallback> result = Collections.unmodifiableList(built);
        log.info("Composio adapter registered {} tool callbacks (truncated={}).", built.size(), registry.wasTruncated());
        return result;
    }
}
