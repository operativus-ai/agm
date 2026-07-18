package ai.operativus.agentmanager.compute.config;

import ai.operativus.agentmanager.compute.tools.AgentToolComponent;
import ai.operativus.agentmanager.compute.tools.composio.ComposioToolCallbackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Domain Responsibility: Aggregates all Spring AI tool callbacks into the single global
 * {@link ToolCallbackProvider} that {@code AgentClientFactory} attaches to every ChatClient.
 * Two sources are merged: (1) compile-time {@code @AgentToolComponent} beans whose
 * {@code @Tool} methods are reflectively registered via {@link MethodToolCallbackProvider};
 * (2) runtime-registered Composio callbacks via {@link ComposioToolCallbackProvider}.
 * State: Stateless (Configuration).
 *
 * <p>Naming-collision check (audit Finding 4): on bean construction, scans
 * {@code @AgentToolComponent} beans for {@code @Tool} methods whose name starts with
 * {@code composio_} — that prefix is RESERVED for the Composio adapter. Logs a startup
 * WARN listing any colliding tools so reviewers see it loudly. (Strict refusal-to-register
 * is deferred — operators may have legitimate one-off needs; WARN gives visibility without
 * blocking deployment.)</p>
 */
@Configuration
public class ToolConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolConfig.class);
    static final String COMPOSIO_RESERVED_PREFIX = "composio_";

    /**
     * @summary Injects the global ToolCallbackProvider for Agent usage.
     * @logic Builds the method-based provider from {@code @AgentToolComponent} beans, captures
     *     a reference to the Composio provider, and returns a composite that re-reads BOTH
     *     sources on every {@code getToolCallbacks()} invocation. Naming-collision scan runs
     *     at construction time. Re-reading is required so that
     *     {@code ComposioConfigChangedEvent}-driven hot reloads of Composio callbacks are
     *     reflected in the global tool surface without app restart — capturing the array at
     *     bean-construction time would freeze the Composio set as of boot.
     */
    @Bean
    public ToolCallbackProvider globalToolProvider(
            ApplicationContext applicationContext,
            ComposioToolCallbackProvider composioProvider) {
        Collection<Object> toolBeans = applicationContext.getBeansWithAnnotation(AgentToolComponent.class).values();
        warnOnReservedPrefixCollision(toolBeans);

        ToolCallbackProvider methodProvider = MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans.toArray())
                .build();

        // Composite — re-reads both sources on every call so Composio hot-reload propagates.
        return () -> {
            ToolCallback[] methodCallbacks = methodProvider.getToolCallbacks();
            ToolCallback[] composioCallbacks = composioProvider.getToolCallbacks();
            if (composioCallbacks.length == 0) {
                return methodCallbacks;
            }
            ToolCallback[] merged = new ToolCallback[methodCallbacks.length + composioCallbacks.length];
            System.arraycopy(methodCallbacks, 0, merged, 0, methodCallbacks.length);
            System.arraycopy(composioCallbacks, 0, merged, methodCallbacks.length, composioCallbacks.length);
            return merged;
        };
    }

    /**
     * Audit Finding 4 + spec §3 Tool-name collision rule. Scans @AgentToolComponent beans for
     * @Tool methods whose annotation name (or method name when unspecified) starts with the
     * reserved {@code composio_} prefix. Logs WARN; does NOT throw (reviewers see the warning
     * loudly, deployment continues).
     */
    private void warnOnReservedPrefixCollision(Collection<Object> toolBeans) {
        for (Object bean : toolBeans) {
            for (Method method : bean.getClass().getDeclaredMethods()) {
                org.springframework.ai.tool.annotation.Tool annotation =
                        method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                if (annotation == null) {
                    continue;
                }
                String declaredName = annotation.name();
                String effectiveName = declaredName.isEmpty() ? method.getName() : declaredName;
                if (effectiveName.startsWith(COMPOSIO_RESERVED_PREFIX)) {
                    log.warn("Tool naming-collision: native tool {}.{} declares @Tool name=\"{}\" which uses the RESERVED \"{}\" prefix. The composio_ prefix is reserved for the Composio dynamic adapter. Rename the native tool to avoid LLM confusion and HITL tier-resolution surprises.",
                            bean.getClass().getSimpleName(), method.getName(), effectiveName, COMPOSIO_RESERVED_PREFIX);
                }
            }
        }
    }
}
