package com.operativus.agentmanager.compute.tools.composio;

import com.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import com.operativus.agentmanager.core.entity.ComposioActionConfig;
import com.operativus.agentmanager.core.event.ComposioConfigChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pins {@link ComposioActionRegistry}'s reload semantics —
 *   DB-vs-properties source-of-truth precedence, atomic state swap on
 *   {@link ComposioConfigChangedEvent}, and cap/warn policy enforcement on both paths.
 *
 * <p>Pure JUnit + Mockito — no Spring context, no Testcontainers. The registry's only
 * collaborator is the repository, which is mocked.
 *
 * State: Stateless (each test instantiates a fresh registry).
 */
class ComposioActionRegistryReloadTest {

    private static final int MAX_ACTIONS = 50;
    private static final int WARN_THRESHOLD = 25;

    @Test
    void registryUsesPropertiesWhenDbEmpty() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo,
                List.of("GMAIL_SEND_EMAIL", "SLACK_LIST_ALL_USERS"),
                MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        assertThat(registry.getEnabledActions())
                .as("DB empty → properties values authoritative")
                .containsExactlyInAnyOrder("GMAIL_SEND_EMAIL", "SLACK_LIST_ALL_USERS");
        assertThat(registry.isEnabled("gmail_send_email")).isTrue(); // case-insensitive
        assertThat(registry.isEnabled("LINEAR_LIST_ISSUES")).isFalse();
        assertThat(registry.wasTruncated()).isFalse();
    }

    @Test
    void registryUsesDbRowsWhenAtLeastOneEnabledRowExists() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName())
                .thenReturn(List.of(actionConfig("LINEAR_LIST_ISSUES"), actionConfig("NOTION_QUERY_DATABASE")));

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo,
                // properties value is intentionally different — must be ignored when DB has rows
                List.of("GMAIL_SEND_EMAIL", "SLACK_LIST_ALL_USERS"),
                MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        assertThat(registry.getEnabledActions())
                .as("DB has rows → DB authoritative; properties ignored (no merge)")
                .containsExactlyInAnyOrder("LINEAR_LIST_ISSUES", "NOTION_QUERY_DATABASE");
        assertThat(registry.isEnabled("GMAIL_SEND_EMAIL")).isFalse();
        assertThat(registry.isEnabled("LINEAR_LIST_ISSUES")).isTrue();
    }

    @Test
    void eventTriggeredReloadSwapsState() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        // First call: DB empty → registry boots from properties
        when(repo.findByEnabledTrueOrderByActionName())
                .thenReturn(List.of())
                .thenReturn(List.of(actionConfig("GITHUB_CREATE_AN_ISSUE")));

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo,
                List.of("GMAIL_SEND_EMAIL"),
                MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        assertThat(registry.getEnabledActions())
                .as("boot path uses properties")
                .containsExactly("GMAIL_SEND_EMAIL");

        // Operator inserts a row → service publishes event → registry reloads
        registry.onComposioConfigChanged(new ComposioConfigChangedEvent("action_create"));

        assertThat(registry.getEnabledActions())
                .as("post-reload uses the new DB-authoritative set")
                .containsExactly("GITHUB_CREATE_AN_ISSUE");
        assertThat(registry.isEnabled("GMAIL_SEND_EMAIL")).isFalse();
    }

    @Test
    void capAndWarnPolicyAppliesOnDbPath() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        // Seed 60 rows from DB — should truncate to 50
        List<ComposioActionConfig> sixty = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> actionConfig("ACTION_" + i))
                .toList();
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(sixty);

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo, List.of(), MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        assertThat(registry.getEnabledCount())
                .as("60 DB rows → truncated to maxActions=50")
                .isEqualTo(MAX_ACTIONS);
        assertThat(registry.wasTruncated()).isTrue();
    }

    @Test
    void dbReadFailureFallsBackToPropertiesGracefully() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName())
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("transient db hiccup"));

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo,
                List.of("BRAVESEARCH_BRAVE_SEARCH"),
                MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        assertThat(registry.getEnabledActions())
                .as("DB exception must not crash boot — fall back to properties")
                .containsExactly("BRAVESEARCH_BRAVE_SEARCH");
    }

    @Test
    void reloadListenersFireSynchronouslyAfterStateSwap() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName())
                .thenReturn(List.of())                                          // boot
                .thenReturn(List.of(actionConfig("GITHUB_CREATE_AN_ISSUE")));   // reload

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo, List.of("GMAIL_SEND_EMAIL"),
                MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        java.util.concurrent.atomic.AtomicReference<Set<String>> snapshotSeenByListener =
                new java.util.concurrent.atomic.AtomicReference<>();
        registry.addReloadListener(() -> snapshotSeenByListener.set(registry.getEnabledActions()));

        registry.onComposioConfigChanged(new ComposioConfigChangedEvent("listener_sync_test"));

        assertThat(snapshotSeenByListener.get())
                .as("listener must observe POST-swap state — not the pre-reload snapshot — "
                        + "otherwise the race between concurrent @EventListener vthreads "
                        + "would let downstream subscribers rebuild against stale registry state")
                .containsExactly("GITHUB_CREATE_AN_ISSUE");
    }

    @Test
    void misbehavingReloadListenerDoesNotBlockOthers() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo, List.of("GMAIL_SEND_EMAIL"),
                MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        java.util.concurrent.atomic.AtomicBoolean siblingRan = new java.util.concurrent.atomic.AtomicBoolean(false);
        registry.addReloadListener(() -> { throw new RuntimeException("subscriber blew up"); });
        registry.addReloadListener(() -> siblingRan.set(true));

        registry.onComposioConfigChanged(new ComposioConfigChangedEvent("listener_isolation_test"));

        assertThat(siblingRan.get())
                .as("one misbehaving listener must not prevent siblings from being notified")
                .isTrue();
    }

    @Test
    void normalizationUppercasesAndDeduplicates() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());

        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo,
                List.of("gmail_send_email", "  GMAIL_SEND_EMAIL  ", "slack_list_all_users", ""),
                MAX_ACTIONS, WARN_THRESHOLD, new SimpleMeterRegistry());

        Set<String> actions = registry.getEnabledActions();
        assertThat(actions)
                .as("UPPERCASE + trim + de-dup + skip blanks")
                .containsExactlyInAnyOrder("GMAIL_SEND_EMAIL", "SLACK_LIST_ALL_USERS");
    }

    private static ComposioActionConfig actionConfig(String actionName) {
        ComposioActionConfig c = new ComposioActionConfig();
        c.setId(actionName.toLowerCase());
        c.setActionName(actionName);
        c.setLlmToolName("composio_" + actionName.toLowerCase());
        c.setTier(1);
        c.setEnabled(true);
        return c;
    }
}
