package com.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.registry.McpOperations;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Requirement 5.8: AgentManager as MCP Server
 * Domain Responsibility: Simple implementation of MCP over SSE+POST. Allows external consumers to connect and execute agents.
 * State: Stateful (maintains active SSE emitters in memory)
 * Dependencies: McpOperations, AgentRegistry
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private final McpOperations agentControlService;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;

    // Store active SSE emitters to push notifications (if needed)
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public McpController(McpOperations agentControlService,
                         AgentRegistry agentRegistry,
                         ObjectMapper objectMapper) {
        this.agentControlService = agentControlService;
        this.agentRegistry = agentRegistry;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect() {
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        
        emitters.put(id, emitter);
        
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));
        
        // MCP: Send endpoint URL as first event
        try {
            emitter.send(SseEmitter.event()
                .name("endpoint")
                .data("/mcp/messages?sessionId=" + id));
            log.info("MCP Client connected: {}", id);
        } catch (IOException e) {
            emitters.remove(id);
        }
        
        return emitter;
    }

    /**
     * ARCH NOTE: the JSON-RPC envelope is inherently dynamic (varies by method, includes
     * both notifications without {@code id} and request/response pairs with {@code id}),
     * so the body is bound as a raw {@code String} and parsed to {@link JsonNode} via the
     * autowired {@link ObjectMapper}. Direct {@code @RequestBody JsonNode} binding fails
     * with {@code HttpMessageConversionException: Type definition error: [simple type,
     * class JsonNode]} under Spring Boot 4 / Jackson 3 in this project. Do not promote
     * the body to a typed record — JSON-RPC method dispatch reads dynamic fields.
     */
    @PostMapping("/messages")
    public Map<String, Object> handleMessage(
            @RequestParam(required = false) String sessionId,
            @RequestBody String rawBody) {

        JsonNode message;
        try {
            message = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            // -32700 Parse Error per JSON-RPC 2.0 spec. id is unknowable (no parse).
            // HashMap (not Map.of) so the spec-required null id can be carried.
            java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", null);
            body.put("error", Map.of("code", -32700, "message", "Parse error"));
            return body;
        }

        // Basic JSON-RPC validation
        if (!message.has("jsonrpc") || !message.has("method")) {
            return error(message.get("id"), -32600, "Invalid Request");
        }

        String method = message.get("method").asText();
        JsonNode id = message.get("id");

        // Metadata-only log — body may contain user-controlled tool arguments. See
        // AugmentedToolCallbackProvider's tool-content gating for the canonical
        // opt-in pattern if protocol-level body visibility is ever needed for debug.
        log.info("MCP Message received: method={} id={}", method, id);

        try {
            return switch (method) {
                case "initialize" -> result(id, Map.of(
                        "protocolVersion", "0.1.0",
                        "server", Map.of("name", "AgentManager", "version", "1.0.0"),
                        "capabilities", Map.of("tools", Map.of())
                ));
                case "notifications/initialized" -> null; // Ack
                
                case "tools/list" -> result(id, Map.of(
                        "tools", getDynamicToolsList()
                ));
                
                case "tools/call" -> handleToolCall(id, message.get("params"));
                
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.error("MCP Error", e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> getDynamicToolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        // System capability to list agents
        tools.add(Map.of(
            "name", "list_agents",
            "description", "List available agents within AgentManager",
            "inputSchema", Map.of("type", "object")
        ));
        
        // Dynamically introspect AgentRegistry to cast every agent as a specific tool
        var agents = agentRegistry.findAll(false,
                com.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId());
        for (var agent : agents) {
            tools.add(Map.of(
                "name", "run_" + agent.id(),
                "description", "Agent Description: " + agent.description(),
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "message", Map.of("type", "string", "description", "The prompt for the agent")
                    ),
                    "required", List.of("message")
                )
            ));
        }
        
        return tools;
    }

    private Map<String, Object> handleToolCall(JsonNode id, JsonNode params) {
        String toolName = params.get("name").asText();
        JsonNode args = params.get("arguments");

        String result;
        if ("list_agents".equals(toolName)) {
            result = agentControlService.list_agents();
        } else if (toolName.startsWith("run_")) {
            String targetAgentId = toolName.substring(4);
            result = agentControlService.run_agent(targetAgentId, args.get("message").asText());
        } else {
            return error(id, -32601, "Tool not found");
        }

        return result(id, Map.of("content", List.of(
                Map.of("type", "text", "text", result)
        )));
    }

    private Map<String, Object> result(JsonNode id, Object result) {
        if (id == null) return null; // Notification
        return Map.of(
            "jsonrpc", "2.0",
            "id", unwrapId(id),
            "result", result
        );
    }

    private Map<String, Object> error(JsonNode id, int code, String message) {
        if (id == null) return null;
        return Map.of(
            "jsonrpc", "2.0",
            "id", unwrapId(id),
            "error", Map.of("code", code, "message", message)
        );
    }

    /**
     * Unwraps a JSON-RPC {@code id} JsonNode to its underlying Java value so it round-trips
     * through Spring's JSON response serializer as a primitive instead of a JsonNode bean
     * (which Jackson 3 would otherwise emit as the JsonNode property map — {@code
     * {array=false, textual=true, ...}} — breaking JSON-RPC correlation for every client).
     * Spec §4: id is String, Number, or null.
     */
    private static Object unwrapId(JsonNode id) {
        if (id == null || id.isNull() || id.isMissingNode()) return "";
        if (id.isTextual()) return id.asText();
        if (id.isIntegralNumber()) return id.asLong();
        if (id.isNumber()) return id.asDouble();
        return id.toString();
    }
}
