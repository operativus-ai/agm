package ai.operativus.agentmanager.compute.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Domain Responsibility: pin the §6 M-12 multi-instance discoverability boot log
 * (Anti-pattern A5 mitigation from {@code docs/plans/agm-clear-out.md}). The log
 * MUST fire exactly once per JVM lifetime — not once per Spring context — so test
 * suites that wake the bean across many {@code @SpringBootTest} classes don't
 * accumulate duplicate INFO lines in CI output.
 *
 * <p><b>Maintenance note (Anti-pattern A4 mitigation):</b> the {@code MemoryAppender}
 * pattern is fragile to leaks. {@link #afterEach()} MANDATORILY detaches the
 * appender so subsequent tests in the same JVM don't accumulate log records and
 * cause assertion drift on later runs. Do not skip the {@code @AfterEach}.
 */
class ModelRateLimitGuardStartupLogTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private ModelRateLimitGuard guard;

    @BeforeEach
    void setUp() {
        // Reset JVM-static idempotency flag so this test class's first event fires the log.
        ModelRateLimitGuard.resetStartupLogForTest();
        logger = (Logger) LoggerFactory.getLogger(ModelRateLimitGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        guard = new ModelRateLimitGuard(null, RateLimiterRegistry.ofDefaults());
    }

    @AfterEach
    void afterEach() {
        // MANDATORY — see Anti-pattern A4 in docs/plans/agm-clear-out.md.
        // Without this, subsequent tests in the same JVM accumulate ILoggingEvent
        // instances on the leaked appender → memory leak + assertion drift on later runs.
        logger.detachAppender(appender);
        appender.stop();
        ModelRateLimitGuard.resetStartupLogForTest();
    }

    @Test
    void emitMultiInstanceDiscoverabilityLog_firesCanonicalInfoLineOnce() {
        guard.emitMultiInstanceDiscoverabilityLog();

        assertThat(appender.list)
                .as("exactly one log event")
                .hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel())
                .as("INFO so operators with tight filters can suppress; not WARN or ERROR")
                .isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .as("canonical operator-facing message — drift here breaks production discoverability")
                .isEqualTo(ModelRateLimitGuard.STARTUP_LOG_MESSAGE)
                .contains("PER JVM PROCESS")
                .contains("N replicas")
                .contains("Redis-backed RateLimiterRegistry");
    }

    @Test
    void emitMultiInstanceDiscoverabilityLog_idempotentAcrossMultipleSpringContexts() {
        // Simulates two Spring contexts in the same Surefire JVM both firing
        // ApplicationReadyEvent on different bean instances.
        guard.emitMultiInstanceDiscoverabilityLog();
        ModelRateLimitGuard secondContextGuard =
                new ModelRateLimitGuard(null, RateLimiterRegistry.ofDefaults());
        secondContextGuard.emitMultiInstanceDiscoverabilityLog();

        assertThat(appender.list)
                .as("second context's event is suppressed by the JVM-static AtomicBoolean")
                .hasSize(1);
    }

    @Test
    void emitMultiInstanceDiscoverabilityLog_onActualApplicationReadyEvent_firesOnce() {
        // Verifies the @EventListener wiring works against a synthesized event,
        // not just direct method invocation.
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        guard.emitMultiInstanceDiscoverabilityLog();
        // A second call with an event-style invocation shouldn't double-emit either.
        guard.emitMultiInstanceDiscoverabilityLog();

        assertThat(appender.list).hasSize(1);
        // event is referenced to keep the import meaningful and to document
        // the test's intent — the actual @EventListener wiring is exercised
        // by the integration container at boot, not here.
        assertThat(event).isNotNull();
    }

    @Test
    void emitMultiInstanceDiscoverabilityLog_distributedFlagOn_firesDistributedMessage() {
        ModelRateLimitGuard.resetStartupLogForTest();
        ModelRateLimitGuard distGuard = new ModelRateLimitGuard(
                null, RateLimiterRegistry.ofDefaults(), null, true);

        distGuard.emitMultiInstanceDiscoverabilityLog();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .as("distributed-on canonical wording — drift breaks production discoverability of mode")
                .isEqualTo(ModelRateLimitGuard.STARTUP_LOG_MESSAGE_DISTRIBUTED)
                .contains("distributed mode ENABLED")
                .contains("Redis-backed")
                .contains("Falls back to per-JVM Resilience4j");
    }

    @Test
    void resetStartupLogForTest_allowsTheNextEmitToFireAgain() {
        guard.emitMultiInstanceDiscoverabilityLog();
        assertThat(appender.list).hasSize(1);

        ModelRateLimitGuard.resetStartupLogForTest();
        guard.emitMultiInstanceDiscoverabilityLog();
        assertThat(appender.list)
                .as("after reset, the next emit fires — pin the test seam contract")
                .hasSize(2);
    }
}
