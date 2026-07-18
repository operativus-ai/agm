package ai.operativus.agentmanager.control.config.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

/**
 * Domain Responsibility: Intercepts incoming HTTP requests to ensure write operations (POST/PUT) with an Idempotency-Key are processed exactly once within a specified time window.
 * State: Stateless (Interceptor)
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    public IdempotencyInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @summary Validates the Idempotency-Key header for POST and PUT requests to prevent duplicate execution.
     * @logic
     * - Checks if the request is a POST or PUT method.
     * - Retrieves the `Idempotency-Key` from HTTP headers.
     * - If present, attempts to atomically set the key in Redis with a 24-hour TTL.
     * - If the key already exists (setIfAbsent returns false), throws a `BusinessValidationException`.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            String idempotencyKey = request.getHeader("Idempotency-Key");
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                String redisKey = "idempotency:" + idempotencyKey;
                Boolean isNewKey;
                try {
                    isNewKey = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", Duration.ofHours(24));
                } catch (org.springframework.dao.DataAccessException e) {
                    // Redis unavailable: the dedup guard is best-effort, so degrade to
                    // allowing the request rather than failing every idempotent mutation.
                    return true;
                }
                if (Boolean.FALSE.equals(isNewKey)) {
                    throw new BusinessValidationException("Duplicate request dropped. Idempotency-Key already seen: " + idempotencyKey);
                }
            }
        }
        return true;
    }
}
