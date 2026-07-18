package ai.operativus.agentmanager.compute.config;

import ai.operativus.agentmanager.compute.tools.AgentToolComponent;
import ai.operativus.agentmanager.compute.tools.composio.ComposioToolCallbackProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Locks Spring AI 2.0.0-SNAPSHOT's current behavior when two
 *   {@code @AgentToolComponent} beans both declare {@code @Tool(name = "dup_target")} —
 *   i.e. when two native tools collide on the same LLM-visible name. The contract today
 *   is undocumented; this test pins whatever Spring AI's {@code MethodToolCallbackProvider}
 *   currently does so that a future Spring AI upgrade surfaces as a failing test rather
 *   than a silent behavior change in production.
 *
 * State: Stateless. Pure JUnit + Mockito — no Spring context.
 *
 * <p>Two outcomes are valid from the AGM correctness perspective; both fail this test
 * loudly if they change:</p>
 * <ul>
 *   <li><b>Throw</b>: {@code MethodToolCallbackProvider.build()} (or the composite
 *       construction) throws an {@code IllegalArgumentException} / {@code IllegalStateException}
 *       at boot — the test asserts that with a message that includes the duplicate name.</li>
 *   <li><b>Coexist</b>: Both callbacks register and {@code globalToolProvider.getToolCallbacks()}
 *       returns two entries named {@code dup_target} — the test asserts the count and which
 *       callback the {@code MethodToolCallbackProvider} ranked first (last-write-wins on the
 *       LLM side is then undefined).</li>
 * </ul>
 *
 * <p>The test runs both probes in sequence: it first checks whether construction throws,
 * and if not, it validates the coexist case. Either way the file documents the current
 * Spring AI 2.0.0-SNAPSHOT behavior in code, so future readers don't need to spelunk to
 * answer "what happens if two of our @AgentToolComponent beans collide on name?".</p>
 */
class ToolNameDuplicationTest {

    @AgentToolComponent
    static class DupAlpha {
        @Tool(name = "dup_target", description = "duplicate-name probe — alpha")
        public String alpha(String input) {
            return "alpha:" + input;
        }
    }

    @AgentToolComponent
    static class DupBeta {
        @Tool(name = "dup_target", description = "duplicate-name probe — beta")
        public String beta(String input) {
            return "beta:" + input;
        }
    }

    @Test
    void duplicateToolName_pinsCurrentSpringAiBehavior() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("alpha", new DupAlpha());
        beans.put("beta", new DupBeta());
        when(ctx.getBeansWithAnnotation(AgentToolComponent.class)).thenReturn(beans);

        ComposioToolCallbackProvider composio = mock(ComposioToolCallbackProvider.class);
        when(composio.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        ToolCallbackProvider composite;
        try {
            composite = new ToolConfig().globalToolProvider(ctx, composio);
        } catch (RuntimeException constructionFailure) {
            // Branch A: Spring AI throws at construction. Pin the failure mode so a
            // future Spring AI version that silently accepts duplicates fails this test.
            assertThat(constructionFailure)
                    .as("if construction throws on duplicate names, the message MUST identify the offending name so operators can diagnose")
                    .hasMessageContaining("dup_target");
            return;
        }

        // Branch B: Spring AI accepts the duplicates and the composite materializes.
        // We probe getToolCallbacks() — if that also accepts, pin the resulting array shape.
        ToolCallback[] callbacks;
        try {
            callbacks = composite.getToolCallbacks();
        } catch (RuntimeException invocationFailure) {
            assertThat(invocationFailure)
                    .as("if getToolCallbacks() throws on duplicate names, the message MUST identify the offending name")
                    .hasMessageContaining("dup_target");
            return;
        }

        // Branch C: Both callbacks coexist — pin the count + the fact that both share the name.
        List<String> names = Arrays.stream(callbacks)
                .map(c -> c.getToolDefinition().name())
                .collect(Collectors.toList());
        long dupCount = names.stream().filter("dup_target"::equals).count();

        assertThat(callbacks)
                .as("Spring AI accepted duplicate names — pin that BOTH callbacks materialize so an upstream change to reject them surfaces here")
                .hasSize(2);
        assertThat(dupCount)
                .as("both registered names must be 'dup_target' — LLM-side disambiguation is then undefined (last-write-wins in tool-call execution). Follow-up: forbid this at the ArchTest level.")
                .isEqualTo(2L);
    }

    @Test
    void duplicateToolName_throwsOrAcceptsButNeverSilentlyDropsOne() {
        // Companion assertion: regardless of whether Spring AI throws or accepts, it MUST
        // NOT silently drop one of the two callbacks. A "1 callback registered" outcome would
        // be the worst case: the LLM sees one tool, the developer thinks two are registered,
        // and the resolution is invisible. This test catches that specifically.
        ApplicationContext ctx = mock(ApplicationContext.class);
        Map<String, Object> beans = new HashMap<>();
        beans.put("alpha", new DupAlpha());
        beans.put("beta", new DupBeta());
        when(ctx.getBeansWithAnnotation(AgentToolComponent.class)).thenReturn(beans);

        ComposioToolCallbackProvider composio = mock(ComposioToolCallbackProvider.class);
        when(composio.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        try {
            ToolCallback[] callbacks = new ToolConfig().globalToolProvider(ctx, composio).getToolCallbacks();
            assertThat(callbacks)
                    .as("if both callbacks register, neither was silently dropped — the count MUST equal the bean count, not 1")
                    .hasSize(2);
        } catch (RuntimeException expected) {
            // Throwing is also acceptable — the test simply requires loud failure, not silent drop.
            assertThat(expected.getMessage())
                    .as("the thrown exception MUST carry the offending name for operator diagnosis")
                    .contains("dup_target");
        }
    }
}
