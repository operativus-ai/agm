# Agent Manager Frontend API Integration

This document outlines the API integrations implemented in the Agent Manager Frontend (`agent-manager-ui`).

## Base Configuration
- **Base URL**: `/api` (Proxied via Vite to Backend)

---

## 1. Agents API Client (`agents-api.ts`)
Handles retrieval of agent configurations.

| Method | Source Function | Endpoint | Description | Return Type |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `getAgents()` | `/api/agents` | Fetch all agent configurations | `Promise<AgentConfig[]>` |
| `GET` | `getAgent(id)` | N/A (Client-side) | Find agent by ID from cache | `Promise<AgentConfig>` |

---

## 2. Chat API Client (`chat-api.ts`)
Handles core agent interaction and execution streams.

| Method | Source Function | Endpoint | Description | Return Type |
| :--- | :--- | :--- | :--- | :--- |
| `POST` | `sendMessage()` | `/api/agents/{agentId}/runs` | Send message (Sync) | `Promise<ChatMessage>` |
| `POST` | `streamMessage()` | `/api/agents/{agentId}/runs/stream` | Send message (SSE Stream) | `Abortable Stream` |
| `POST` | `continueRun()` | `/api/agents/{agentId}/runs/{runId}/continue` | Approve/Reject action | `Promise<RunResponse>` |

### Key Types
**RunRequest**:
```typescript
interface RunRequest {
  message: string;
  stream: boolean;
  session_id?: string;
  media?: MediaInput[];
}
```

---

## 3. Knowledge API Client (`knowledge-api.ts`)
Handles document management for RAG.

| Method | Source Function | Endpoint | Description | Return Type |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `getDocuments()` | `/api/knowledge` | List all documents | `Promise<KnowledgeDocument[]>` |
| `POST` | `uploadDocument()` | `/api/knowledge/upload` | Upload file (`FormData`) | `Promise<KnowledgeUploadResponse>` |
| `DELETE` | `deleteDocument()` | `/api/knowledge/{id}` | Delete document | `Promise<void>` |

---

## 4. Shared Types (`types/api.ts`)
Common interfaces used across the application.

```typescript
export interface AgentConfig {
  agent_id: string;
  name: string;
  description: string;
  model: string;
  is_reasoning_enabled?: boolean;
}
```
