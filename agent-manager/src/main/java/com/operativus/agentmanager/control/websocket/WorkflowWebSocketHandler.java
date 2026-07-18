package com.operativus.agentmanager.control.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Handles real-time WebSocket connections and broadcasts asynchronous workflow execution events to connected clients.
 * State: Stateful (Maintains active WebSocketSession registry)
 */
@Component
public class WorkflowWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWebSocketHandler.class);
    
    // Maps workflowId -> List/Set of active sessions listening to that workflow
    // For simplicity in Phase 3, we map sessionId -> WebSocketSession and broadcast globally based on attributes
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("New WebSocket connection established: {}", session.getId());
        sessions.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket connection closed: {}", session.getId());
        sessions.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Received WebSocket message from {}: {}", session.getId(), message.getPayload());
        // In Operativus, clients might send "ping" or "subscribe:workflowId" messages.
    }

    /**
     * Broadcasts a JSON event to all connected clients.
     * In a production multi-tenant scenario, this would be scoped to connections 
     * that have authenticated and subscribed to specific `workflowId`s.
     */
    public void broadcastEvent(String workflowId, String eventType, Object payload) {
        try {
            String jsonMessage = mapper.writeValueAsString(Map.of(
                    "workflow_id", workflowId,
                    "event", eventType,
                    "payload", payload,
                    "timestamp", System.currentTimeMillis()
            ));
            
            TextMessage textMessage = new TextMessage(jsonMessage);
            
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        } catch (IOException e) {
            log.error("Failed to serialize or send WebSocket event", e);
        }
    }
}
