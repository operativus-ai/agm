# Agent Manager Backend API Documentation

This document outlines the REST API endpoints available in the Agent Manager backend (`agent-manager`).

## Base URL
All API endpoints are relative to the server base URL (default: `http://localhost:8080`).

---

## 1. Agents API (`/api/agents`)
Manage AI agents and their execution runs.

| Method | Endpoint | Description | Request Body | Response |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/` | List all registered agents | - | `List<AgentDefinition>` |
| `GET` | `/{agentId}` | Get specific agent details | - | `AgentDefinition` |
| `POST` | `/{agentId}/knowledge/load` | Trigger knowledge loading for agent | - | `String` (Status message) |
| `POST` | `/{agentId}/runs` | Execute an agent run (Synchronous) | `RunRequest` | `RunResponse` |
| `POST` | `/{agentId}/runs/stream` | Execute an agent run (Streaming SSE) | `RunRequest` | `Flux<AgentStreamEvent>` |
| `POST` | `/{agentId}/runs/background` | Queue a background run | `RunRequest` | `Map` (run_id, status) |
| `POST` | `/{agentId}/runs/{runId}/continue` | Submit human input/action to paused run | `ContinueRequest` | `RunResponse` |
| `GET` | `/{agentId}/runs/{runId}/status` | Get status of a specific run | - | `AgentRun` entity |
| `DELETE` | `/{agentId}/runs/{runId}` | Cancel a specific run | - | `204 No Content` |

### Data Models
**RunRequest**:
```json
{
  "message": "User query",
  "session_id": "optional-uuid",
  "stream": true,
  "media": [{"type": "image/png", "data": "base64..."}]
}
```

**ContinueRequest**:
```json
{
  "action": "APPROVE" // or "REJECT"
}
```

---

## 2. Configuration API (`/config`)
System-level configuration and bootstrapping.

| Method | Endpoint | Description | Response |
| :--- | :--- | :--- | :--- |
| `GET` | `/` | Get system config, agent list, and knowledge bases | `Map` (os_id, agents, knowledge_bases, etc.) |

---

## 3. Knowledge API (`/api/knowledge`)
Manage RAG (Retrieval-Augmented Generation) documents.

| Method | Endpoint | Description | Request Body | Response |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/` | List knowledge documents | - | `List<KnowledgeContent>` |
| `POST` | `/upload` | Upload a document | `MultipartFile` (file) | `Map` (status, message) |
| `DELETE` | `/{id}` | Delete a document | - | `204 No Content` |
| `GET` | `/search` | Semantic search | `?query=string` | `List<Document>` |

---

## 4. Session API (`/api/sessions`)
Manage agent interaction sessions (history).

| Method | Endpoint | Description | Query Params | Response |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/` | List sessions | `userId`, `agentId` | `List<AgentSession>` |
| `GET` | `/{sessionId}` | Get session details | - | `AgentSession` |
| `GET` | `/{sessionId}/runs` | Get execution history for session | - | `List<AgentRun>` |
| `DELETE` | `/{sessionId}` | Delete session | - | `204 No Content` |

---

## 5. Memory API (`/api/memories`)
Manage long-term user memory (Semantic/Episodic).

| Method | Endpoint | Description | Request Body | Response |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `/` | Add a memory manually | `{"content": "..."}` | `Map` (status) |
| `GET` | `/` | Search memories | `?query=string` | `List<String>` |
| `DELETE` | `/` | Delete specific memories | `List<String>` (IDs) | `204 No Content` |
| `POST` | `/optimize` | Trigger memory optimization/consolidation | `?userId=...` | `Map` (status) |
| `GET` | `/stats` | Get memory statistics | `?userId=...` | `Map` (stats) |
| `GET` | `/topics` | Get memory topics | `?userId=...` | `List<String>` |

---

## 6. Evaluations API (`/evals`)
Agent performance evaluation endpoints.

| Method | Endpoint | Description | Request Body | Response |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/` | List evaluations | - | `List<Evaluation>` |
| `POST` | `/` | Create new evaluation | `Map` (agent_id, name, etc.) | `Evaluation` |
| `POST` | `/{id}/result` | Record evaluation result | `Map` (output, score) | `Evaluation` |

---

## 7. MCP Server API (`/mcp`)
Model Context Protocol implementation.

| Method | Endpoint | Description | Notes |
| :--- | :--- | :--- | :--- |
| `GET` | `/sse` | MCP Handshake | Returns SSE Stream |
| `POST` | `/messages` | JSON-RPC Message Handler | Follows MCP Spec |
