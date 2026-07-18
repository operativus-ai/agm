---
name: mcp-server-expert
description: Exposes the Agent Manager platform natively as an MCP Server, allowing external IDEs (Cursor) or external Agents to consume its tools natively.
tags: [mcp, server, sse, spring-boot]
---

# MCP Server Expert

## Purpose
Your job is to architect the outbound API endpoints that allow the Agent Manager to ACT as an MCP Server. This means turning our internal Agent swarms, Database evaluators, and Knowledge Base vector searches into raw JSON-RPC `@McpTool` definitions that external clients can discover and execute over an SSE stream.

## Implementation Architecture

You MUST:
1. **Conform to the MCP Spec:** Strictly adhere to the Anthropic Model Context Protocol JSON-RPC specification.
2. **SSE Streaming (WebFlux):** Set up a dedicated `/mcp/sse` Spring `RestController` that returns a continuous `Flux<ServerSentEvent<String>>` or utilizes Spring WebFlux SSE endpoints for the persistence layer connection.
3. **Message Routing:** Create a robust POST endpoint for `message` transmissions that maps JSON-RPC `tools/list` and `tools/call` to our internal Spring Services.
4. **Authentication:** Do not expose the MCP endpoint publicly. It must be gated by our API Key or JWT Bearer token system inherited from our `ChatApi`.

## Constraints
- **Strict Isolation:** Ensure that external agents calling our tools via this MCP Server only receive scoped access. They must not bypass the standard RAG restrictions or delete agents.
- **Async Execution:** Heavy evaluations or swarm requests triggered via MCP `tools/call` MUST run on Virtual Threads to prevent blocking the WebFlux SSE event loop.

---

## Output
- `McpServerController` exposing the SSE and Message POST routes.
- Component models that auto-generate JSON schemas for our internal functions.
