package ai.operativus.agentmanager.integration.tools;

import ai.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import ai.operativus.agentmanager.core.entity.ComposioActionConfig;
import ai.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins that {@code ComposioConfigChangedEvent} round-trips through the
 *   composite {@code ToolConfig.globalToolProvider} surface — adding a row + publishing the
 *   event makes the new {@code composio_*} callback appear via the composite within polling
 *   time without an app restart, and disabling the row removes it.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. Cleans up its own DB row in a finally block to be polite to
 *   the post-test TRUNCATE (which also covers it).
 *
 * <p>{@code ComposioActionRegistryReloadTest} already pins registry-level reload behavior
 * with pure mocks. This test pins the seam from "registry mutated" → "composite returns the
 * new callback set", which is the production contract that matters for live operator edits.
 * The lambda inside {@code ToolConfig.globalToolProvider} re-reads
 * {@code composioProvider.getToolCallbacks()} on every call — caching it would silently
 * break runtime reload, and this test catches that.</p>
 *
 * <p><strong>Production fix shipped in this PR</strong>: {@code ToolConfig.globalToolProvider}
 * previously captured {@code composioProvider.getToolCallbacks()} as an array at
 * bean-construction time, freezing the Composio surface to the boot snapshot —
 * {@code ComposioConfigChangedEvent} would update the provider's internal state but the
 * composite would never read it. Writing this test surfaced the bug. The lambda now re-reads
 * both sources on every invocation, which makes hot-reload actually propagate end-to-end.
 * The {@code ToolConfigCompositeTest.composioCallbacksAreReReadOnEveryCall_notFrozenAtBoot}
 * case pins the new contract at the unit level; this test pins it at the SpringBootTest
 * level through the real event multicaster.</p>
 *
 * <p><strong>Listener race regression-lock</strong>: {@code AgentManagerConfig} wires an
 * async {@code ApplicationEventMulticaster}. Previously, both {@code ComposioActionRegistry}
 * and {@code ComposioToolCallbackProvider} listened to {@code ComposioConfigChangedEvent}
 * directly, so the provider could rebuild against a pre-swap registry snapshot — masked here
 * by an Awaitility re-publish loop. The provider now subscribes to the registry's synchronous
 * {@code addReloadListener} hook, which fires only AFTER the state swap. This test publishes
 * the event EXACTLY ONCE and polls only for the listener-thread to land; a regression of the
 * fix (e.g. provider re-acquires a direct {@code @EventListener}) would flake this assertion
 * before the timeout.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
class ComposioHotReloadCompositeRuntimeTest extends BaseIntegrationTest {

    private static final String ACTION_NAME = "AGM_TEST_HOTRELOAD_ACTION";
    private static final String EXPECTED_TOOL_NAME = "composio_agm_test_hotreload_action";

    @DynamicPropertySource
    static void enableComposioAdapterWithFakeKey(DynamicPropertyRegistry registry) {
        // A non-blank api-key activates the Composio provider so callbacks are built.
        // No HTTP fires in this test — we never invoke a callback, we only assert presence.
        registry.add("agent.tools.composio.api-key", () -> "fake-key-for-hot-reload-test");
    }

    @Autowired
    private ToolCallbackProvider globalToolProvider;

    @Autowired
    private ComposioActionConfigRepository actionRepo;

    @Autowired
    private ApplicationEventPublisher events;

    @Test
    void enableRow_publishEvent_compositeShowsNewCallback_then_disableRow_removesIt() {
        Set<String> initial = currentNames();
        assertThat(initial)
                .as("the test action MUST NOT be registered before the row exists — otherwise the assertion below is meaningless")
                .doesNotContain(EXPECTED_TOOL_NAME);

        ComposioActionConfig row = new ComposioActionConfig(UUID.randomUUID().toString(), ACTION_NAME, 2);
        row.setEnabled(true);
        try {
            actionRepo.saveAndFlush(row);
            // Publish EXACTLY ONCE — a regression of the synchronous-handoff fix would let the
            // provider rebuild against the pre-swap registry snapshot, and the poll below would
            // never see the new callback.
            events.publishEvent(new ComposioConfigChangedEvent("hotreload_test_enable"));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(150))
                    .untilAsserted(() -> assertThat(currentNames())
                            .as("after enabling row + publishing event, the composite must expose the new composio_* callback without app restart")
                            .contains(EXPECTED_TOOL_NAME));

            // Disable and confirm removal — proves the lambda in ToolConfig.globalToolProvider
            // re-reads composioProvider.getToolCallbacks() rather than caching the prior array.
            row.setEnabled(false);
            actionRepo.saveAndFlush(row);
            events.publishEvent(new ComposioConfigChangedEvent("hotreload_test_disable"));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(150))
                    .untilAsserted(() -> assertThat(currentNames())
                            .as("after disabling row + publishing event, the composite must drop the callback")
                            .doesNotContain(EXPECTED_TOOL_NAME));
        } finally {
            // Defence in depth — BaseIntegrationTest's @AfterEach TRUNCATE covers this, but
            // an explicit delete keeps this test self-contained if the base changes.
            try {
                actionRepo.deleteById(row.getId());
                actionRepo.flush();
            } catch (RuntimeException ignored) {
                // Row may already be absent if the test failed mid-flight.
            }
            events.publishEvent(new ComposioConfigChangedEvent("hotreload_cleanup"));
        }
    }

    private Set<String> currentNames() {
        ToolCallback[] cbs = globalToolProvider.getToolCallbacks();
        return Arrays.stream(cbs).map(c -> c.getToolDefinition().name()).collect(Collectors.toSet());
    }
}
