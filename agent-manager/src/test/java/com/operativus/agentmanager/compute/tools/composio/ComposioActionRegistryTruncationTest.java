package com.operativus.agentmanager.compute.tools.composio;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin {@link ComposioActionRegistry}'s cap-and-warn policy at the
 *   format-string and metric levels — the silent-truncation path was previously only
 *   inferred from a sibling test's javadoc. This class makes it a property-by-test.
 *
 * <p>Asserts on the {@code TRUNCATION_LOG_MESSAGE_FORMAT} / {@code WARN_THRESHOLD_LOG_MESSAGE_FORMAT}
 *   / {@code LOADED_LOG_MESSAGE_FORMAT} constants on the production class — never on string
 *   literals — so a refactor of the message wording does not silently break log-based
 *   alerting.
 *
 * <p>Counter assertion: each truncation increments {@code agm.composio.actions.truncated}
 *   tagged with {@code source} ({@code db} or {@code properties}) by the count of dropped
 *   actions. Sub-cap loads do NOT touch the counter.
 *
 * State: Stateless (each test instantiates a fresh registry + fresh `SimpleMeterRegistry`).
 */
class ComposioActionRegistryTruncationTest {

    private static final int MAX_ACTIONS = 50;
    private static final int WARN_THRESHOLD = 25;

    private Logger registryLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        registryLogger = (Logger) LoggerFactory.getLogger(ComposioActionRegistry.class);
        appender = new ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        registryLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void overCap_truncatesToMax_logsAtError_incrementsCounterByExcess() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());

        // 60 unique action names; max=50 → 10 truncated.
        List<String> sixty = IntStream.range(0, 60)
                .mapToObj(i -> "ACTION_" + i)
                .toList();

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo, sixty, MAX_ACTIONS, WARN_THRESHOLD, meters);

        assertThat(registry.getEnabledCount())
                .as("size capped at maxActions")
                .isEqualTo(MAX_ACTIONS);
        assertThat(registry.wasTruncated())
                .as("overCap flag set on truncation")
                .isTrue();

        assertThat(appender.list)
                .as("ERROR log emitted with the canonical TRUNCATION_LOG_MESSAGE_FORMAT")
                .anyMatch(ev -> ev.getLevel() == Level.ERROR
                        && ev.getMessage().equals(ComposioActionRegistry.TRUNCATION_LOG_MESSAGE_FORMAT));

        // Properties-source path; counter increment = 60 - 50 = 10.
        double propertiesCounter = meters.find("agm.composio.actions.truncated")
                .tag("source", "properties").counter().count();
        double dbCounter = meters.find("agm.composio.actions.truncated")
                .tag("source", "db").counter().count();
        assertThat(propertiesCounter)
                .as("properties-source counter increments by (size - cap)")
                .isEqualTo(10.0);
        assertThat(dbCounter)
                .as("db-source counter is untouched on a properties-fallback truncation")
                .isEqualTo(0.0);
    }

    @Test
    void overWarnButUnderCap_logsAtWarn_doesNotIncrementCounter() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());

        // 30 unique action names; warn=25, max=50 → above warn, below cap.
        List<String> thirty = IntStream.range(0, 30)
                .mapToObj(i -> "ACTION_" + i)
                .toList();

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo, thirty, MAX_ACTIONS, WARN_THRESHOLD, meters);

        assertThat(registry.getEnabledCount()).isEqualTo(30);
        assertThat(registry.wasTruncated()).isFalse();

        assertThat(appender.list)
                .as("WARN log uses the canonical WARN_THRESHOLD_LOG_MESSAGE_FORMAT")
                .anyMatch(ev -> ev.getLevel() == Level.WARN
                        && ev.getMessage().equals(ComposioActionRegistry.WARN_THRESHOLD_LOG_MESSAGE_FORMAT));

        // Counter must NOT be touched — warn-only path.
        double propertiesCounter = meters.find("agm.composio.actions.truncated")
                .tag("source", "properties").counter().count();
        assertThat(propertiesCounter)
                .as("warn-only path does NOT increment the truncation counter")
                .isEqualTo(0.0);
    }

    @Test
    void underWarn_logsAtInfo_doesNotIncrementCounter() {
        ComposioActionConfigRepository repo = mock(ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        // Empty list also exercises the zero-element edge — pinned by the test's caption.
        ComposioActionRegistry registry = new ComposioActionRegistry(
                repo, List.of(), MAX_ACTIONS, WARN_THRESHOLD, meters);

        assertThat(registry.getEnabledCount()).isZero();
        assertThat(registry.wasTruncated()).isFalse();

        assertThat(appender.list)
                .as("INFO log uses the canonical LOADED_LOG_MESSAGE_FORMAT")
                .anyMatch(ev -> ev.getLevel() == Level.INFO
                        && ev.getMessage().equals(ComposioActionRegistry.LOADED_LOG_MESSAGE_FORMAT));

        double propertiesCounter = meters.find("agm.composio.actions.truncated")
                .tag("source", "properties").counter().count();
        assertThat(propertiesCounter).isEqualTo(0.0);
    }
}
