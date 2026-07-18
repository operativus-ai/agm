package com.operativus.agentmanager.control.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The handler's whole point is to swallow exceptions instead of re-throwing — these
 * tests exist to fail loudly the moment that contract changes back. The test that
 * caught the live bug was its absence: every {@code @Cacheable} call was a hard
 * Redis dependency until this class existed.
 */
class LoggingCacheErrorHandlerTest {

    private final LoggingCacheErrorHandler handler = new LoggingCacheErrorHandler();

    @Test
    void handleCacheGetError_DoesNotPropagate_TreatedAsMiss() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("agentRegistry");
        RedisConnectionFailureException e = new RedisConnectionFailureException("Unable to connect to Redis");

        assertDoesNotThrow(() -> handler.handleCacheGetError(e, cache, "key-1"));
    }

    @Test
    void handleCachePutError_DoesNotPropagate() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("agentRegistry");
        RedisConnectionFailureException e = new RedisConnectionFailureException("Unable to connect to Redis");

        assertDoesNotThrow(() -> handler.handleCachePutError(e, cache, "key-1", "value"));
    }

    @Test
    void handleCacheEvictError_DoesNotPropagate() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("agentRegistry");
        RedisConnectionFailureException e = new RedisConnectionFailureException("Unable to connect to Redis");

        assertDoesNotThrow(() -> handler.handleCacheEvictError(e, cache, "key-1"));
    }

    @Test
    void handleCacheClearError_DoesNotPropagate() {
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("agentRegistry");
        RedisConnectionFailureException e = new RedisConnectionFailureException("Unable to connect to Redis");

        assertDoesNotThrow(() -> handler.handleCacheClearError(e, cache));
    }
}
