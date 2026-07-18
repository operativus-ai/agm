package com.operativus.agentmanager.integration.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;

/**
 * Domain Responsibility: Supplies a {@link NoOpCacheManager} for integration tests.
 *   The production {@code CacheConfig} only registers its {@code RedisCacheManager} when
 *   {@code spring.cache.type=redis} (the prod default via {@code matchIfMissing=true});
 *   the test profile sets {@code spring.cache.type=none}, so this bean fills the gap.
 *   Without it, {@code @EnableCaching} fails to start the context.
 * State: Stateless.
 *
 * Why no-op (vs. a real in-memory cache like {@code ConcurrentMapCacheManager}): cached
 * results would mask per-request behavior across tests — repository calls would return
 * stale values populated by an earlier test's context. A no-op forces every call to
 * round-trip the DB, which is the runtime behavior the integration suite is meant to pin.
 */
@TestConfiguration
public class NoOpCacheConfig {

    @Bean(name = "cacheManager")
    public CacheManager cacheManager() {
        return new NoOpCacheManager();
    }
}
