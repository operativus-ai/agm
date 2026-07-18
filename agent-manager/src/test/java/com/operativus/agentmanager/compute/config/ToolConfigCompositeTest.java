package com.operativus.agentmanager.compute.config;

import com.operativus.agentmanager.compute.tools.AgentToolComponent;
import com.operativus.agentmanager.compute.tools.composio.ComposioToolCallbackProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins
 *   {@link ToolConfig#globalToolProvider(ApplicationContext, ComposioToolCallbackProvider)}
 *   merge semantics — assembly order (native-first, composio-second), the composio-empty
 *   early-return branch, and the both-empty no-NPE boundary.
 *
 * State: Stateless. Pure JUnit + Mockito — no Spring context boot, no DB.
 *
 * <p>Complements {@code ComposioAdapterRuntimeTest} (Composio-only paths) by isolating
 * the composite seam itself: a refactor of the lambda inside {@code globalToolProvider}
 * (caching, reordering, parallel streams) that breaks the contract surfaces here, not
 * at LLM-call time where the failure mode is silent.</p>
 */
class ToolConfigCompositeTest {

    @AgentToolComponent
    static class NativeAlpha {
        @Tool(name = "tc_alpha", description = "alpha tool for composite test")
        public String alpha(String input) {
            return "alpha:" + input;
        }
    }

    @AgentToolComponent
    static class NativeBeta {
        @Tool(name = "tc_beta", description = "beta tool for composite test")
        public String beta(String input) {
            return "beta:" + input;
        }
    }

    @Test
    void mixed_nativeFirstThenComposio_inMergedArray() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("alpha", new NativeAlpha());
        beans.put("beta", new NativeBeta());
        when(ctx.getBeansWithAnnotation(AgentToolComponent.class)).thenReturn(beans);

        ToolCallback comp1 = stubCallback("composio_one");
        ToolCallback comp2 = stubCallback("composio_two");
        ComposioToolCallbackProvider composio = mock(ComposioToolCallbackProvider.class);
        when(composio.getToolCallbacks()).thenReturn(new ToolCallback[]{comp1, comp2});

        ToolCallbackProvider composite = new ToolConfig().globalToolProvider(ctx, composio);
        ToolCallback[] all = composite.getToolCallbacks();

        assertThat(all).hasSize(4);
        Set<String> nativeNames = Arrays.stream(all).limit(2)
                .map(c -> c.getToolDefinition().name()).collect(Collectors.toSet());
        List<String> composioNames = Arrays.stream(all).skip(2)
                .map(c -> c.getToolDefinition().name()).collect(Collectors.toList());
        assertThat(nativeNames)
                .as("native callbacks occupy the first slots (set-equality only — reflection method order is JVM-dependent)")
                .containsExactlyInAnyOrder("tc_alpha", "tc_beta");
        assertThat(composioNames)
                .as("composio callbacks occupy the trailing slots in the order the provider returned them — System.arraycopy preserves it")
                .containsExactly("composio_one", "composio_two");
    }

    @Test
    void composioEmpty_returnsNativeCallbacksOnly() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("alpha", new NativeAlpha());
        when(ctx.getBeansWithAnnotation(AgentToolComponent.class)).thenReturn(beans);

        ComposioToolCallbackProvider composio = mock(ComposioToolCallbackProvider.class);
        when(composio.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        ToolCallbackProvider composite = new ToolConfig().globalToolProvider(ctx, composio);

        assertThat(composite.getToolCallbacks()).hasSize(1);
        assertThat(composite.getToolCallbacks()[0].getToolDefinition().name()).isEqualTo("tc_alpha");
    }

    @Test
    void bothEmpty_returnsZeroCallbacksNoNpe() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansWithAnnotation(AgentToolComponent.class)).thenReturn(new HashMap<>());

        ComposioToolCallbackProvider composio = mock(ComposioToolCallbackProvider.class);
        when(composio.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        ToolCallbackProvider composite = new ToolConfig().globalToolProvider(ctx, composio);

        assertThat(composite.getToolCallbacks())
                .as("zero native + zero composio → empty callback array, not null, no NPE")
                .isEmpty();
    }

    @Test
    void composioCallbacksAreReReadOnEveryCall_notFrozenAtBoot() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("alpha", new NativeAlpha());
        when(ctx.getBeansWithAnnotation(AgentToolComponent.class)).thenReturn(beans);

        ToolCallback first = stubCallback("composio_first");
        ToolCallback second = stubCallback("composio_second");
        ComposioToolCallbackProvider composio = mock(ComposioToolCallbackProvider.class);
        when(composio.getToolCallbacks())
                .thenReturn(new ToolCallback[]{first})        // first invocation
                .thenReturn(new ToolCallback[]{first, second}); // second invocation (post hot-reload)

        ToolCallbackProvider composite = new ToolConfig().globalToolProvider(ctx, composio);

        assertThat(composite.getToolCallbacks())
                .as("first call sees the composio provider's initial single-callback snapshot")
                .hasSize(2); // 1 native + 1 composio
        assertThat(composite.getToolCallbacks())
                .as("second call MUST re-read composioProvider — capturing the array at bean-construction time would freeze hot reload")
                .hasSize(3); // 1 native + 2 composio
    }

    private ToolCallback stubCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("stub for " + name)
                .inputSchema("{}")
                .build();
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }
}
