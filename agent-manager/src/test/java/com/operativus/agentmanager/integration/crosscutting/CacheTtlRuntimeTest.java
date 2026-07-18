package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.control.service.SettingsService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box coverage of the §27.10 Redis TTL contract.
 *   {@link com.operativus.agentmanager.control.config.CacheConfig} configures a
 *   {@link RedisCacheManager} with {@code entryTtl} sourced from
 *   {@code spring.cache.redis.time-to-live} (prod default PT24H). This test pins:
 *   <ul>
 *     <li>The configured TTL is actually applied — cache entries disappear after the window.</li>
 *     <li>The cache manager under test really is Redis-backed (not a Caffeine/NoOp fallback),
 *         so the assertion is about Redis server-side expiry, not an in-memory LRU.</li>
 *   </ul>
 *
 * <p>Harness: a dedicated {@code redis:7-alpine} {@link GenericContainer} is spun up on a
 * random port and {@code spring.cache.redis.time-to-live} is pinned to {@code PT2S} so the
 * test can observe expiry without sleeping 24 hours. {@code spring.cache.type=redis}
 * overrides the {@code application-test.properties} default of {@code none} for this class
 * only; sibling tests that set {@code spring.cache.type=none} are unaffected.</p>
 *
 * State: Stateless (per-test DB truncation; the Redis container is shared across tests in
 *   this class via static init, same pattern as the Postgres container in
 *   {@link BaseIntegrationTest}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
@TestPropertySource(properties = {
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=PT2S",
        "spring.data.redis.repositories.enabled=false"
})
public class CacheTtlRuntimeTest extends BaseIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private CacheManager cacheManager;
    @Autowired private SettingsService settingsService;

    // §27.10 case — the 24-hour production TTL contract: entries written to the cache must
    // expire after the configured window. We pin PT2S via @TestPropertySource so the test
    // completes in ~3s instead of a day. If the property is dropped from CacheConfig (regressing
    // to a hardcoded 24h) this test fails because the entry would still be present after sleep.
    @Test
    void cacheEntriesExpireAfterConfiguredTtl() throws Exception {
        assertInstanceOf(RedisCacheManager.class, cacheManager,
                "CacheManager must be RedisCacheManager under spring.cache.type=redis — "
                        + "got " + cacheManager.getClass().getName() + " which means the "
                        + "Redis profile didn't activate and this test is asserting nothing.");

        // Seed a row so the cached map is non-empty. An empty HashMap still deserializes
        // round-trip fine, but having a concrete value makes the cache-write proof obvious.
        jdbc.update("""
                INSERT INTO app_settings (setting_key, setting_value, description)
                VALUES (?, ?, ?)
                """, "cache.ttl.probe", "probe-value", "TTL test probe");

        // Prime the cache via the @Cacheable proxy (SettingsService.getAllSettings uses
        // cache 'settings' with key 'all').
        Map<String, String> first = settingsService.getAllSettings();
        assertNotNull(first, "precondition — getAllSettings must not return null");
        assertTrue(first.containsKey("cache.ttl.probe"),
                "precondition — getAllSettings must return the seeded row");

        Cache settings = cacheManager.getCache("settings");
        assertNotNull(settings, "'settings' cache must be created on first @Cacheable call");

        // Poll for visibility (≤ 1s) instead of asserting immediately. Spring Data Redis's
        // RedisCache.put with the Lettuce driver does not synchronously block on Redis's SET
        // ack — under load, separate write/read connections can race and an immediate get
        // returns null even though the SET is in flight. Production code never reads a
        // @Cacheable entry sub-millisecond after writing it (it just returns the method's
        // result), so this is a test-only timing concern, not a cache-correctness regression.
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(1))
                .pollInterval(java.time.Duration.ofMillis(20))
                .untilAsserted(() -> assertNotNull(settings.get("all"),
                        "entry under key 'all' must become visible within 1s of the @Cacheable "
                                + "call. A persistent null here means the write didn't reach Redis "
                                + "(serialization error?) and the TTL assertion would be a false positive."));

        // Wait past the pinned 2s TTL. Add headroom for Redis's expiry sweep + JVM scheduling.
        Thread.sleep(3_500);

        Cache.ValueWrapper afterExpiry = settings.get("all");
        assertNull(afterExpiry,
                "cache entry under 'all' must be expired after 2s TTL + 1.5s slack = 3.5s total. "
                        + "A non-null ValueWrapper here means either the spring.cache.redis.time-to-live "
                        + "property isn't being honored by CacheConfig, or the TTL is hardcoded again.");

        // Sanity: a fresh @Cacheable call after expiry must repopulate the cache (poll
        // for visibility for the same Lettuce-async reason as above).
        settingsService.getAllSettings();
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(1))
                .pollInterval(java.time.Duration.ofMillis(20))
                .untilAsserted(() -> assertNotNull(settings.get("all"),
                        "post-expiry read must repopulate the cache. If still null after 1s, "
                                + "the @Cacheable write path is broken — not a TTL issue."));
    }
}
