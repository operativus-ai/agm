package ai.operativus.agentmanager.control.config;

import ai.operativus.agentmanager.control.websocket.WorkflowWebSocketHandler;
import ai.operativus.agentmanager.control.security.JwtHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Domain Responsibility: Configures WebSocket endpoints and handlers for real-time bi-directional communication (e.g., streaming agent workflows).
 * State: Stateless (Configuration)
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorkflowWebSocketHandler workflowWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    public WebSocketConfig(WorkflowWebSocketHandler workflowWebSocketHandler, JwtHandshakeInterceptor jwtHandshakeInterceptor) {
        this.workflowWebSocketHandler = workflowWebSocketHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
    }

    /**
     * @summary Registers WebSocket handlers and configures their endpoint behaviors.
     * @logic
     * - Maps the `WorkflowWebSocketHandler` to the `/workflows/ws` endpoint.
     * - Intercepts the handshake to perform JWT authentication before upgrading the connection.
     * - Configures CORS strictly for WebSocket connections.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(workflowWebSocketHandler, "/workflows/ws")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*"); // Open CORS for local dev across Vite/React port 5173
    }
}
