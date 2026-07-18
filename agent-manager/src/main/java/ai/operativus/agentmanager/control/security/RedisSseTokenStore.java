package ai.operativus.agentmanager.control.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Redis-backed implementation of {@link SseTokenStore}.
 *   Production-preferred for cluster deployments. Tokens survive single-node restarts and
 *   are visible to every app instance behind the same Redis.
 * State: One {@link RedisTemplate} bean per app; Redis is the source of truth.
 *
 * <p><b>Single-use enforcement:</b> {@link #validateAndConsume} executes a Lua script that
 * GETs and DELs in one atomic pass — Redis guarantees the script body runs uninterrupted, so
 * no second caller can observe the token after the first consume.
 *
 * <p><b>Mismatched-run handling:</b> the Lua script only DELs when the stored {@code runId}
 * matches the caller's expected run. Mismatches return the value without removing it,
 * preserving the 60s window for the legitimate caller.
 */
@Service
@ConditionalOnProperty(name = "agm.sse.token-store", havingValue = "redis")
public class RedisSseTokenStore implements SseTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSseTokenStore.class);

    private static final String KEY_PREFIX = "agm:sse-token:";

    /**
     * Lua: returns the JSON string and deletes the key only when the embedded "runId" field
     * matches ARGV[1]. If the key is absent, returns nil. If the runId mismatches, returns
     * the JSON string but does NOT delete (so the rightful caller can still consume).
     *
     * The runId match is done by Lua string.find, not full JSON parsing, to keep the script
     * tiny. Token claims are produced exclusively by {@link #store} so the JSON shape is
     * stable: {@code "runId":"<value>"} appears verbatim. We anchor the match with the quoted
     * field name + colon to avoid coincidental substring matches.
     */
    private static final String VALIDATE_AND_CONSUME_LUA = """
            local v = redis.call('GET', KEYS[1])
            if not v then return nil end
            local pat = '"runId":"' .. ARGV[1] .. '"'
            if string.find(v, pat, 1, true) then
              redis.call('DEL', KEYS[1])
              return {v, 1}
            end
            return {v, 0}
            """;

    private final RedisTemplate<String, SseTokenClaim> redisTemplate;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> consumeScript;
    private final Clock clock;

    @Autowired
    public RedisSseTokenStore(RedisConnectionFactory connectionFactory) {
        this(connectionFactory, Clock.systemUTC());
    }

    RedisSseTokenStore(RedisConnectionFactory connectionFactory, Clock clock) {
        this.redisTemplate = new RedisTemplate<>();
        this.redisTemplate.setConnectionFactory(connectionFactory);
        this.redisTemplate.setKeySerializer(new StringRedisSerializer());
        this.redisTemplate.setValueSerializer(new Jackson2JsonRedisSerializer<>(SseTokenClaim.class));
        this.redisTemplate.afterPropertiesSet();
        this.consumeScript = new DefaultRedisScript<>(VALIDATE_AND_CONSUME_LUA, List.class);
        this.clock = clock;
    }

    @Override
    public void store(String token, SseTokenClaim claim, long ttlSeconds) {
        redisTemplate.opsForValue().set(key(token), claim, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<SseTokenClaim> validateAndConsume(String token, String expectedRunId) {
        try {
            List<Object> result = redisTemplate.execute(
                    consumeScript,
                    List.of(key(token)),
                    expectedRunId);
            if (result == null || result.isEmpty()) return Optional.empty();
            String json = (String) result.get(0);
            Long matched = ((Number) result.get(1)).longValue();
            if (matched != 1L) return Optional.empty();
            SseTokenClaim claim = redisTemplate.<SseTokenClaim>getValueSerializer() == null
                    ? null
                    : deserialize(json);
            if (claim == null) return Optional.empty();
            if (Instant.now(clock).isAfter(claim.expiresAt())) return Optional.empty();
            return Optional.of(claim);
        } catch (RuntimeException ex) {
            log.warn("RedisSseTokenStore: validateAndConsume failed for token prefix {}",
                    safePrefix(token), ex);
            return Optional.empty();
        }
    }

    @Override
    public Optional<SseTokenClaim> peek(String token) {
        SseTokenClaim claim = redisTemplate.opsForValue().get(key(token));
        if (claim == null) return Optional.empty();
        if (Instant.now(clock).isAfter(claim.expiresAt())) return Optional.empty();
        return Optional.of(claim);
    }

    private static String key(String token) {
        return KEY_PREFIX + token;
    }

    private static String safePrefix(String token) {
        return token == null ? "<null>" : (token.length() <= 8 ? "***" : token.substring(0, 4) + "***");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SseTokenClaim deserialize(String json) {
        Jackson2JsonRedisSerializer<SseTokenClaim> serializer =
                (Jackson2JsonRedisSerializer<SseTokenClaim>) (Jackson2JsonRedisSerializer) redisTemplate.getValueSerializer();
        return serializer.deserialize(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
