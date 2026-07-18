# AgentManager Java Microservice Specification

**Version:** 1.0  
**Target Architecture:** Stateless REST/SSE API backed by PostgreSQL (PgVector)  
**Framework:** Spring Boot 3.5.7+ / Spring AI 1.0+

## Executive Summary

This document specifies the architecture for a Java-native Agentic Operating System (AgentManager) designed to achieve functional parity with Operativus (formerly Phidata).

### Core Philosophy

- **Three-Layer Architecture**:
  - **Framework**: Spring AI (Java) replaces the Operativus Python SDK for defining Agents, Tools, and Workflows.
  - **Runtime**: A stateless, horizontally scalable Spring Boot WebFlux application replaces the FastAPI AgentManager runtime.
  - **Control Plane**: The system must expose standard endpoints (`/config`, `/runs`) to connect with the Operativus UI (or a custom React frontend) for monitoring and management.
- **Private by Design**: All data (Memory, Knowledge, Logs) must reside strictly within the user's infrastructure (PostgreSQL/Redis). No user data shall be transmitted to external SaaS observability platforms unless explicitly configured [Source: 1810].
- **Performance First**: To match Operativus's ~2µs instantiation times, the system must utilize Java 21 Virtual Threads and avoid heavy reflection during the hot execution path [Source: 1727].

---

## Technology Stack Requirements

This section defines the mandatory technology stack required to achieve performance parity with Operativus (which uses asyncio and uvloop) and support the advanced reasoning loops defined in the functional specifications.

### Core Runtime

The runtime environment is centered around Project Loom, allowing Java to match the high-concurrency, non-blocking performance of Python's uvloop.

#### Performance & Runtime Mandates

**Requirement:** To approximate Operativus's ~3µs instantiation benchmark and support high-concurrency agent workloads, the Java runtime must adhere to the following strict performance architecture:

1. **Virtual Threads (Project Loom)**: All Agent execution loops and I/O binding (Database/LLM calls) MUST run on `Dispatchers.LOOM` (Java 21+). Do not use platform threads for agent tasks.
2. **Class Data Sharing (CDS)**: The production build process must generate a CDS archive (`java -Xshare:dump`) to minimize JVM startup latency and memory footprint.
3. **Stateless Architecture**: The `AgentService` must be a singleton that instantiates `ChatClient` objects per-request using the prototype pattern. No conversational state shall be held in JVM heap memory between requests; all state must be rehydrated from the Database.

| Component | Requirement | Justification |
| :--- | :--- | :--- |
| **Language** | Java 21+ | Mandatory. Required for Virtual Threads (Project Loom). Operativus agents operate asynchronously with extremely low overhead (~3µs instantiation). |
| **Framework** | Spring Boot 3.4+ | Native support for Virtual Threads (`spring.threads.virtual.enabled=true`) and compatibility with latest Spring AI milestones. |
| **AI Framework** | Spring AI 1.1.0-M4+ | Critical. Older versions lack the `ToolCallAdvisor` (Recursive Advisor) required for Operativus's "Think-Act-Observe" loops. |
| **Build Tool** | Maven 3.9+ / Gradle 8+ | Standard dependency management. |

### Persistence & Memory

Operativus uses a "Private by Design" architecture where all state resides in the user's infrastructure. We standardize on PostgreSQL as the unified backend for relational data, JSON documents, and vector embeddings.

| Component | Requirement | Justification |
| :--- | :--- | :--- |
| **Database** | PostgreSQL 16+ | Serves as "Contents DB" and "Vector DB". Version 16+ offers improved JSONB performance. |
| **Vector Extension** | pgvector v0.7.0+ | Required for HNSW indexing to enable low-latency semantic retrieval. |
| **Connection Pool** | HikariCP | Optimized configuration for Virtual Threads is required (avoid pinning). |

### Interface & API

The interface layer mimics Operativus's real-time streaming capabilities while simplifying the developer experience.

| Component | Requirement | Justification |
| :--- | :--- | :--- |
| **Web Layer** | Spring WebMvc | Configured with Virtual Threads. Reactive Stack (WebFlux) is not required due to Loom. |
| **API Spec** | OpenAPI 3.1 | Required for compatibility with Operativus Control Plane and MCP definitions. |
| **Streaming** | Server-Sent Events (SSE) | Must support `text/event-stream` to stream token generation and reasoning steps. |

### Infrastructure & Security

| Component | Requirement | Justification |
| :--- | :--- | :--- |
| **Containerization** | Docker | Microservice must be containerized for ECS/K8s environments. |
| **Sandbox** | Testcontainers | Required for "Secure Sandboxing" if agents execute code (e.g., Python REPL). |
| **Auth** | Spring Security (OAuth2) | Enforces RBAC on Agents and Teams, mirroring Operativus's middleware security. |

---

## Functional Specifications

### Core Agent Runtime (AgentService)

**Requirement:** The service must dynamically construct `ChatClient` instances per request to ensure strict isolation of user state and configuration.

- **Reasoning Loop**: The runtime must implement a "Think → Act → Observe" loop.
- **Implementation**: Use Spring AI 1.1.x Recursive Advisors (`ToolCallAdvisor`) to handle multi-turn tool execution automatically [Source: 697, 701].

#### Inner Thought Capture (Explainability)

**Requirement:** The runtime must capture the agent's internal reasoning before a tool is executed, mirroring Operativus's "Reasoning" features.

- **Mechanism**: Use Spring AI `AugmentedToolCallbackProvider` to dynamically inject an `innerThought` field into tool schemas.
- **Behavior**: The LLM fills this field explaining why it is calling the tool. The thought is logged to the `run_steps` table but stripped before passing arguments to the actual Java method [Source: 782, 786].

#### Agent Response Schema & Streaming Contract

**Requirement:** The AgentManager Runtime must expose a unified response structure supporting both synchronous execution and SSE streaming.

##### A. Synchronous Response (RunResponse)

Used for non-streaming endpoints (e.g., automated workflows) [Source: 182, 1241].

```java
public record RunResponse(
    String runId,
    String sessionId,
    String content,              // Final Markdown response
    Map<String, Object> metadata,// Usage stats, model info
    List<ToolCall> tools,        // Audit log of tools called
    List<String> reasoningSteps, // Captured "Inner Thoughts"
    String status                // "COMPLETED", "FAILED"
) {}
```

##### B. Streaming Response (Server-Sent Events)

Used for real-time UI rendering [Source: 182, 1242, 1214].

```java
public record AgentStreamEvent(
    EventType event,      // Enum discriminator
    String data,          // Text delta or JSON payload
    Long timestamp
) {}

public enum EventType {
    START,                // Stream initialized
    REASONING_DELTA,      // "Thinking..." text
    CONTENT_DELTA,        // Final answer text
    TOOL_START,           // "Calling tool: ..."
    TOOL_END,             // "Tool finished: ..."
    STOP,                 // Stream complete
    ERROR
}
```

- **Reasoning Logic**: Emit `REASONING_DELTA` events for traces captured via `AugmentedToolCallbackProvider` [Source: 1587, 1593].

##### C. Artifact Response (Multimodal)

Used for generated files (images, audio, PDF) [Source: 991, 1531].

```java
public record ArtifactEvent(
    String fileId,
    String type,          // "IMAGE", "AUDIO", "PDF"
    String url,           // Presigned download URL
    String mimeType
) {}
```

### Memory & Persistence Architecture

**Requirement:** Implement a "Split-Brain" memory architecture.

1. **Episodic Memory (Session History)**:
   - **Storage**: `agent_sessions` table (JSONB).
   - **Lifecycle**: Loaded via `MessageChatMemoryAdvisor`.
2. **Semantic Memory (User Facts)**:
   - **Storage**: `agent_memories` table (PgVector).
   - **Lifecycle**: Managed via Agentic Memory Tools (`save_memory`, `search_memory`) [Source: 1586].

### Agentic RAG (KnowledgeBase)

**Requirement:** Move beyond passive Context Injection. The Agent must possess a Tool to search knowledge actively.

- **Tool**: `search_knowledge_base(query: String)`
- **Search Strategy**: Implement Hybrid Search (Keyword + Vector) using PostgreSQL `tsvector` and `pgvector` [Source: 1560].

### Team Orchestration

**Requirement:** Support Operativus’s two primary Team modes [Source: 1284].

1. **Coordinator Mode**: A "Leader" Agent uses tools to delegate tasks to "Member" Agents.
2. **Router Mode**: The Leader classifies intent and hands off the session entirely via `RoutingWorkflow`.

---

## API Specifications

### System Configuration API

**Requirement:** Expose system metadata in the format expected by the Operativus Control Plane.

- **Endpoint**: `GET /config`

**Response Schema (JSON):**

```json
{
  "os_id": "unique-os-id",
  "name": "Java AgentManager",
  "version": "1.0.0",
  "agents": [
    {
      "id": "finance_agent",
      "name": "Finance Assistant",
      "description": "Analyzes stock data",
      "is_team": false
    }
  ],
  "teams": [],
  "workflows": [],
  "knowledge_bases": [
    {
      "id": "company_docs",
      "name": "Corporate Policy"
    }
  ]
}
```

### Knowledge Management Endpoints

**Requirement:** Expose CRUD operations for knowledge management via the UI.

- `POST /knowledge/add`: Trigger async ingestion pipeline.
- `GET /knowledge/list`: Paginated list with status (`PROCESSING`, `COMPLETED`, `FAILED`).
- `DELETE /knowledge/{id}`: Cascading delete (metadata + vector rows).

### Asynchronous/Background Execution

**Requirement:** Support long-running tasks via `background=True` [Source: 1331].

- `POST /agent/run/background`: Returns `202 Accepted` with `run_id`.
- `GET /agent/run/{run_id}/status`: Returns `RUNNING`, `COMPLETED`, or `FAILED`.

### Multimodal Capabilities

**Requirement:** Accept and return non-text media [Source: 1004, 1658].

```java
public record MediaInput(
    String type,      // "IMAGE" | "AUDIO"
    String data,      // Base64 or URL
    String mimeType   // "image/png"
) {}
```

---

## Security & Guardrails

**Requirement:** Enforcement of "Private by Design" principles. Intercept requests before they reach the Agent Runtime [Source: 1812, 1813].

### Perimeter Security

- **Authentication**: Spring Security 6.0+ (OAuth2) [Source: 297, 1175].
- **Context Extraction**: Inject `user_id` and `org_id` into `AdviseContext` for strict partitioning [Source: 298, 711].

### Input Guardrails (Pre-Execution)

Use `CallAdvisor` with `Ordered.HIGHEST_PRECEDENCE` [Source: 141, 1391, 1392].

1. **PromptInjectionAdvisor**: Detect jailbreak attempts.
2. **PIIAnonymizationAdvisor**: Redact sensitive data before prompt construction.

### Output Guardrails (Post-Execution)

1. **ContentSafetyAdvisor**: Check against moderation endpoints [Source: 150].
2. **HallucinationCheckAdvisor**: Verify cited sources in RAG responses.

### Secure Sandboxing

**Requirement:** Isolate code execution (e.g., Python REPL) using ephemeral Docker containers [Source: 540, 545].

---

## Durable Workflows & Runtime

### Split-Runtime Architecture

Distinguish between **Deterministic Workflows** (Code) and **Probabilistic Agents** (Inference).

- **The Agent**: Atomic unit wrapping `ChatModel`, `Tools`, and `Knowledge` [Source: 381, 1370, 678].
- **The Workflow**: Sequence of `Steps` with persistent `session_state` [Source: 1632].

### Database Schema Specification

#### Session Storage (`agent_sessions`)

```sql
CREATE TABLE agent_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255),
    session_state JSONB DEFAULT '{}',
    messages JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### Knowledge Contents (`knowledge_contents`)

```sql
CREATE TABLE knowledge_contents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(50),
    uri TEXT,
    content_hash VARCHAR(64),
    status VARCHAR(50) DEFAULT 'PROCESSING',
    vector_ids UUID[],
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### Semantic Memory (`agent_memories`)

```sql
CREATE TABLE agent_memories (
    id UUID PRIMARY KEY,
    content TEXT,
    embedding vector(1536),
    user_id VARCHAR(255),
    memory_type VARCHAR(50)
);
```

### Reasoning Architecture

#### Reasoning Tools (Explicit)

```java
@Component("reasoningTools")
public class ReasoningTools {
    @Tool(description = "Plan your next step or analyze results.")
    public String think(@ToolParam(description = "Analysis content") String thought) {
        return "Thought recorded.";
    }
}
```

### Human-in-the-Loop (HITL)

**Requirement:** "Pause & Resume" for sensitive tools [Source: 800, 801, 1680].

1. **Suspension**: advisor detects `@RequiresConfirmation` -> Serialize -> `PAUSED`.
2. **Resumption**: `POST /runs/{runId}/continue` -> Execute tools -> Feed back to LLM.

---

## Implementation Roadmap

| Phase | Goal | Key Tasks |
| :--- | :--- | :--- |
| **Phase 1** | **OS Foundation** | PostgreSQL + pgvector, Schema Migration, Entities. |
| **Phase 2** | **Control Plane Bridge** | `/config` endpoint, Dynamic Agent Factory, SSE Streaming. |
| **Phase 3** | **Intelligence** | Agentic RAG tool, Reasoning Traces, MCP Integration. |
| **Phase 4** | **Hardening** | HITL State Machine, LLM-as-a-Judge Evaluation suite. |

### Sample Configuration

```java
@Configuration
public class AgentManagerConfig {
    @Bean
    @Scope("prototype")
    public ChatClient agentClient(ChatClient.Builder builder, String agentId) {
        return builder
            .defaultAdvisors(new ToolCallAdvisor(true)) 
            .build();
    }
}
```

---

## Migration & Performance

### Concept Mapping

| Operativus (Python) | Java AgentManager Equivalent | Note |
| :--- | :--- | :--- |
| `Agent(knowledge=...)` | `@Tool search_knowledge_base` | Active tool usage [Source: 340]. |
| `Team(mode="coordinate")` | `OrchestratorService` | Router/Orchestrator patterns [Source: 1031]. |
| `storage=SqliteDb` | `JdbcChatMemory` | Use PostgreSQL for concurrency [Source: 1623]. |

### Performance Optimization

- **Startup**: Use Spring AOT or GraalVM Native Images for low-latency instantiation [Source: 1729].
- **Concurrency**: Use Java 21 Virtual Threads (`Dispatcher.LOOM`) for high-concurrency loops [Source: 534].
- **Context Caching**: Order `Advisors` to place static prompts (System, Tools) first for efficient cache hits.