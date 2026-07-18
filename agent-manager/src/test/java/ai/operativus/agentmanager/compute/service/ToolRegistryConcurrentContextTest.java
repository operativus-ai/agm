package ai.operativus.agentmanager.compute.service;

import ai.operativus.agentmanager.control.security.AgentContextHolder;
import ai.operativus.agentmanager.core.model.TeamManifest.AgentManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins that {@link ToolRegistry}'s OBO security proxy correctly
 *   isolates {@link AgentContextHolder.AgentContext} bindings across concurrent virtual
 *   threads — each VT's tool invocation sees ONLY its own ScopedValue-bound context,
 *   never another VT's. This is the substrate for every OBO capability check in the
 *   codebase, so a bleed here would silently break the per-agent identity sandbox.
 *
 * State: Stateless. Pure JUnit + Mockito + virtual-thread executor. No Spring context.
 *
 * <p>The existing {@code ToolRegistryTest} covers single-threaded happy path and
 * single-threaded throw-on-no-context. This test covers what the single-threaded cases
 * cannot: ScopedValue inheritance under VT spawn, which is the production execution
 * substrate (virtual threads are enabled via {@code spring.threads.virtual.enabled=true}).</p>
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryConcurrentContextTest {

    private static final int CONCURRENT_VTS = 64;

    @Mock
    private ApplicationContext applicationContext;

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(applicationContext);
    }

    @Test
    void concurrent_uniqueContextsPerVt_noBleed() throws Exception {
        // Register a tool that returns the current binding's teamId — the OBO proxy reads
        // the ScopedValue inside the lambda at invocation time, so the returned value
        // proves which context was active when this VT's call ran.
        Function<String, String> identityProbe = arg -> AgentContextHolder.getContext().teamId();
        toolRegistry.register("identityProbe", identityProbe);

        @SuppressWarnings("unchecked")
        Function<Object, Object> tool = (Function<Object, Object>) toolRegistry.getTool("identityProbe");
        assertThat(tool).isNotNull();

        // Map from the bound teamId → the teamId the tool actually saw. If isolation
        // holds, every entry maps to itself. If anything bleeds, the values diverge.
        Map<String, String> observed = new HashMap<>(CONCURRENT_VTS);
        Map<String, String> expected = IntStream.range(0, CONCURRENT_VTS).boxed()
                .collect(Collectors.toMap(i -> "team-" + i, i -> "team-" + i));

        try (ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Map.Entry<String, String>>> futures = IntStream.range(0, CONCURRENT_VTS)
                    .mapToObj(i -> vts.submit(() -> {
                        String teamId = "team-" + i;
                        AgentManifest manifest = new AgentManifest("agent-" + i, "Agent " + i,
                                Collections.emptyList(), false);
                        AgentContextHolder.AgentContext ctx = new AgentContextHolder.AgentContext(
                                teamId, "user-" + i, 100.0, manifest);
                        String seen = ScopedValue.where(AgentContextHolder.CONTEXT, ctx)
                                .call(() -> (String) tool.apply("payload-" + i));
                        return Map.entry(teamId, seen);
                    }))
                    .toList();

            for (var f : futures) {
                var entry = f.get(10, TimeUnit.SECONDS);
                observed.put(entry.getKey(), entry.getValue());
            }
        }

        assertThat(observed)
                .as("every VT must see exactly its own bound teamId — any deviation means ScopedValue is bleeding across VTs, which would silently subvert every OBO capability check in the codebase")
                .containsExactlyInAnyOrderEntriesOf(expected);
    }
}
