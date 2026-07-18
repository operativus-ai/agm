# Feature Parity Gap Analysis

Analysis of the `agent-manager` backend java microservice against the Operativus Procurator Specification.

## 1. URL & API Structure

The `agent-manager` uses a flatter, RPC-style URL structure compared to the RESTful resource-oriented design of Operativus Procurator.

| Feature | Operativus Spec (Planned) | Current Implementation | Gap / Action |
| :--- | :--- | :--- | :--- |
| **Agents List** | `GET /agents` | `GET /api/agents` | **Minor**: URL prefix mismatch (`/api` vs root). |
| **Agent Details** | `GET /agents/{id}` | *Missing* | **Create Endpoint**. |
| **Agent Run** | `POST /agents/{id}/runs` | `POST /agent/run` | **Refactor**: Move logic to `AgentController`. Support path param. |
| **Run Stream** | `POST ...?stream=true` | `POST /agent/stream` | **Refactor**: Consolidate into single endpoint with `stream` query param or header. |
| **Run Cancel** | `POST .../cancel` | *Missing* | **Create Endpoint**. |
| **Sessions List** | `GET /sessions` | `GET /api/sessions` | **Minor**: URL prefix. Add filtering (User, Agent, Date). |
| **Knowledge** | `GET /knowledge` | `GET /api/knowledge` | **Minor**: URL prefix. |

## 2. Missing Endpoints

The following features defined in the Operativus spec are entirely missing from the Java backend:

### Memory
-   `PATCH /memories/{memory_id}`: Update memory content.
-   `DELETE /memories`: Batch delete.
-   `POST /optimize-memories`: Trigger LLM consolidation.
-   `GET /memory_topics`: List topics.
-   `GET /user_memory_stats`: Usage stats.

### Knowledge (RAG)
-   `GET /knowledge/search`: Semantic search API (present in Tool, missing in Controller).

### System
-   `GET /metrics`: Prometheus endpoint (Enabled in properties, just need to verify exposure).

## 3. Implementation Plan

### Phase 1: Controller Refactoring (REST Standardization)
Refactor existing controllers to match Operativus's URL structure (`/api` prefix is fine/standard in Java, but the resource paths should match).

1.  **AgentController**:
    -   Move `run`, `stream`, `continue` from `ChatController` to `AgentController`.
    -   Implement `GET /agents/{agentId}`.
    -   Implement `POST /agents/{agentId}/runs` (Consolidated stream/non-stream).

2.  **SessionController**:
    -   Add filtering params to `listSessions` (userId, agentId, dateRange).

### Phase 2: Feature Implementation

1.  **Memory Optimization**:
    -   Implement `MemoryService.optimizeMemories(userId)`.
    -   Use `ChatClient` to summarize existing memories and replace them in the DB.

2.  **Knowledge Search**:
    -   Expose `vectorStore.similaritySearch()` via `KnowledgeController`.

3.  **Run Management**:
    -   Implement "Cancel Run" (Requires `Map<RunId, Future>` tracking in `AgentService`).

## 4. UI Control Plane Parity

Analysis of the `agent-manager-ui` frontend's ability to expose all configuration parameters defined by the backend `AgentDefinition`.

| Feature | Description | Status |
| :--- | :--- | :--- |
| **Tabbed Configuration UI** | Left column of `AgentFormModal` uses a split-pane tabbed view to prevent vertical stretching and fit 15+ complex configs. | ✓ Completed |
| **Team Orchestration UI** | Supervisor toggle (`isTeam`), Orchestration Mode (`teamMode`), and dynamic Tag-based selection for Sub-Agent `members`. | ✓ Completed |
| **Security Boundaries UI** | Form controls for `requiresPiiRedaction`, `approvedForProduction`, `maintenanceMode`, and Tag-based `allowedRoles`. | ✓ Completed |
| **Advanced Inference UI** | Toggles for `isReasoningEnabled` and `enforceJsonOutput`. Includes UUID-validated Tag-based selection for `knowledgeBaseIds`. | ✓ Completed |
| **Array Input UX** | Implemented `TagInput` component to elegantly handle string arrays natively (Comma/Enter delimiters, invalid tag rejection). | ✓ Completed |

---

# Verification
- Use `curl` or Postman to verify the new backend endpoints against the spec.
- Run `start-dev.sh` and perform a full "Chat with Memory" loop.
- Validate `AgentFormModal` interactions for invalid UUIDs or malformed Agent IDs in the TagInputs.
