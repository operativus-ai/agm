package ai.operativus.agentmanager.compute.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Domain Responsibility: Subscribes to Redis PubSub channels for extension lifecycle events,
 * enabling cluster-wide synchronization of MCP connection state. When any node registers
 * or deletes an MCP extension, all nodes in the cluster receive the event and update
 * their local McpConnectionPool accordingly.
 *
 * @architecture Uses Spring Data Redis MessageListener infrastructure.
 *               Channel topics: "extension:registered", "extension:deleted".
 *               Message format: "extensionId" for both registration and deletion — the pool
 *               re-loads the row (url / transport / decrypted auth) from the DB, so no secret
 *               crosses the wire.
 *               Property-gated by agm.mcp.cluster-events.enabled (default true) so test
 *               profile can opt out — the RedisMessageListenerContainer implements
 *               SmartLifecycle and opens a Redis connection at context start(), which
 *               fails every @SpringBootTest when no Redis is provisioned.
 * State: Stateless (delegates to McpConnectionPool)
 */
@Configuration
@ConditionalOnProperty(name = "agm.mcp.cluster-events.enabled", havingValue = "true", matchIfMissing = true)
public class McpClusterEventListener {

    private static final Logger log = LoggerFactory.getLogger(McpClusterEventListener.class);

    public static final String CHANNEL_REGISTERED = "extension:registered";
    public static final String CHANNEL_DELETED = "extension:deleted";

    private final McpConnectionPool connectionPool;

    public McpClusterEventListener(McpConnectionPool connectionPool) {
        this.connectionPool = connectionPool;
    }

    /**
     * @summary Configures the Redis PubSub listener container for extension lifecycle events.
     * @logic Creates a RedisMessageListenerContainer and subscribes two channels:
     *        one for registration events and one for deletion events.
     */
    @Bean
    public RedisMessageListenerContainer extensionEventListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.addMessageListener(registrationListener(), new ChannelTopic(CHANNEL_REGISTERED));
        container.addMessageListener(deletionListener(), new ChannelTopic(CHANNEL_DELETED));

        log.info("McpClusterEventListener: Subscribed to Redis PubSub channels [{}, {}]", CHANNEL_REGISTERED, CHANNEL_DELETED);
        return container;
    }

    /**
     * @summary Handles extension registration events from Redis PubSub.
     * @logic The message body is the extension id; delegates to McpConnectionPool.connect(id),
     *         which loads the row (url / transport / decrypted auth) from the DB.
     */
    @Bean
    public MessageListener registrationListener() {
        return (Message message, byte[] pattern) -> {
            String body = new String(message.getBody()).trim();
            log.info("McpClusterEventListener: Received extension:registered event: {}", body);
            try {
                if (!body.isEmpty()) {
                    connectionPool.connect(body);
                } else {
                    log.warn("McpClusterEventListener: Malformed registration event: empty id");
                }
            } catch (Exception e) {
                log.error("McpClusterEventListener: Failed to process registration event: {}", e.getMessage());
            }
        };
    }

    /**
     * @summary Handles extension deletion events from Redis PubSub.
     * @logic Parses the message as "extensionId" and delegates to McpConnectionPool.disconnect().
     */
    @Bean
    public MessageListener deletionListener() {
        return (Message message, byte[] pattern) -> {
            String body = new String(message.getBody());
            log.info("McpClusterEventListener: Received extension:deleted event: {}", body);
            try {
                connectionPool.disconnect(body.trim());
            } catch (Exception e) {
                log.error("McpClusterEventListener: Failed to process deletion event: {}", e.getMessage());
            }
        };
    }
}
