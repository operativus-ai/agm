package com.operativus.agentmanager.control.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Domain Responsibility: Cache error policy that downgrades Redis failures to cache
 *     misses instead of letting them propagate as request-killing 500s. Logs at WARN
 *     so operators still see the underlying connectivity problem in the BE log.
 * State: Stateless.
 *
 * <p>The default Spring policy is {@code SimpleCacheErrorHandler}, which re-throws.
 * That made every {@code @Cacheable} call across the platform a hard dependency on
 * Redis being reachable — a transient blip on the Lettuce client took down endpoints
 * like {@code /api/monitoring/stats} that only ever cached read-side rollups. Treating
 * cache failures as misses preserves correctness (the underlying method still runs)
 * at the cost of a short latency hit during the outage window.
 *
 * <p>Wired via {@code CacheConfig.errorHandler()} (CachingConfigurer override).
 */
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache GET failed (cache={}, key={}): {} — falling through to underlying method",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache PUT failed (cache={}, key={}): {} — value not cached this round",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache EVICT failed (cache={}, key={}): {} — entry may remain stale until TTL",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("Cache CLEAR failed (cache={}): {} — entries may remain stale until TTL",
                cache.getName(), exception.toString());
    }
}
