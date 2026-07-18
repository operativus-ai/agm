package com.operativus.agentmanager.integration.extensions;

import com.operativus.agentmanager.core.spi.AgentHookExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link AgentHookExtension} used by
 * {@link ExtensionSpiVsDbPrecedenceRuntimeTest} to prove that
 * {@code ExtensionHookAdvisor.dispatchHook} fires the SPI hook (not the DB-registered
 * webhook) when both share the same {@code extensionId}. A static {@link AtomicInteger}
 * pair tracks how many times {@code beforeExecution} / {@code afterExecution} fire so the
 * test can assert SPI dispatch happened without observing HTTP.
 *
 * <p>The fixed {@code EXTENSION_ID} is unique to this test (prefix {@code e6-spi-collision-})
 * so no other test in the suite attaches it to an agent.</p>
 *
 * <p>Discovered via the test-classpath service-loader file
 * {@code src/test/resources/META-INF/services/com.operativus.agentmanager.core.spi.AgentHookExtension}.
 * This makes the SPI visible to ALL integration tests that boot the full context, but the
 * id is namespaced so non-E6 tests never reference it.</p>
 */
public class E6TestHookSpi implements AgentHookExtension {

    public static final String EXTENSION_ID = "e6-spi-collision-fixed-id";

    /** Pre-hook invocations. Reset via {@link #resetCounters()} per-test. */
    public static final AtomicInteger PRE_DISPATCH_COUNT = new AtomicInteger();
    /** Post-hook invocations. Reset via {@link #resetCounters()} per-test. */
    public static final AtomicInteger POST_DISPATCH_COUNT = new AtomicInteger();

    public static void resetCounters() {
        PRE_DISPATCH_COUNT.set(0);
        POST_DISPATCH_COUNT.set(0);
    }

    @Override
    public String getExtensionId() {
        return EXTENSION_ID;
    }

    @Override
    public String beforeExecution(String runId, String rawInput, Map<String, Object> context) {
        PRE_DISPATCH_COUNT.incrementAndGet();
        return rawInput;
    }

    @Override
    public String afterExecution(String runId, String llmOutput, Map<String, Object> context) {
        POST_DISPATCH_COUNT.incrementAndGet();
        return llmOutput;
    }
}
