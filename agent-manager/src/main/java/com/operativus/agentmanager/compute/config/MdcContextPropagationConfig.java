package com.operativus.agentmanager.compute.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Domain Responsibility: Registers the Micrometer {@link Slf4jThreadLocalAccessor} on the
 *   global {@link ContextRegistry}. Once registered, any call to
 *   {@code ContextSnapshotFactory.builder().build().captureAll().wrap(...)} captures the
 *   parent thread's SLF4J {@link org.slf4j.MDC} snapshot and restores it on the forked
 *   (virtual) thread when the wrapped {@code Runnable} / {@code Callable} runs.
 *
 * <p>This is the auto-propagation piece that pairs with
 * {@link com.operativus.agentmanager.compute.config.AgentMdcFilter} and
 * {@link com.operativus.agentmanager.core.callback.AgentContextHolder#populateMdcFromScopedValues()}.
 * Without it, background tasks launched via {@code ContextSnapshotFactory} on virtual threads
 * start with an empty MDC — which is why RunExecutionManager / WorkflowService / the team
 * orchestrators all call {@code populateMdcFromScopedValues()} as their first line after the
 * wrap boundary. Those manual bridges are still load-bearing for ScopedValue → MDC conversion
 * (Micrometer does not ship a ScopedValue accessor in 1.2), but MDC → MDC no longer needs
 * hand-rolled propagation.</p>
 *
 * <p>Registration runs in {@link PostConstruct}. {@link ContextRegistry#getInstance()} is a
 * JVM-wide singleton, so a single registration covers every ContextSnapshot consumer in the
 * process (Spring AI advisors, the background job queue, stream orchestrators).</p>
 *
 * State: Stateless (configuration only — all state lives on the global registry).
 */
@Configuration
public class MdcContextPropagationConfig {

    private static final Logger log = LoggerFactory.getLogger(MdcContextPropagationConfig.class);

    @PostConstruct
    public void registerMdcAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
        log.info("Registered Slf4jThreadLocalAccessor — SLF4J MDC will now auto-propagate via Micrometer ContextSnapshot");
    }
}
