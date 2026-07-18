# Agent Manager — Backend

The Spring Boot backend for the **Agent Manager** agentic control plane. Provides the full runtime for orchestrating, governing, and operating AI agents at scale: LLM provider abstraction, multi-agent coordination, run lifecycle management, compliance enforcement, cost accountability, HITL, A2A, and deep observability — all behind a single REST API.

---

## Tech Stack

| Layer | Technology | Version |
| --- | --- | --- |
| Language | Java (virtual threads enabled) | 25 |
| Framework | Spring Boot | 4.0.0-SNAPSHOT |
| AI | Spring AI | 2.0.0-SNAPSHOT |
| Modulith | Spring Modulith | 1.4.0-SNAPSHOT |
| Database | PostgreSQL + pgvector | 17 |
| Cache | Redis | 7 |
| Tracing | Jaeger / OTLP | latest |
| Schema | Liquibase | — |
| Auth | JWT (OAuth2 Resource Server) | — |

> The project uses snapshot repositories (`spring-milestones`, `spring-snapshots`). Do not remove them from `pom.xml`.

---

## Prerequisites

- **Java 25** — `JAVA_HOME` must point to a Java 25 JDK
- **Docker + Docker Compose** — required for the local infrastructure stack
- Maven wrapper (`./mvnw`) is included — do not use a system Maven

---

## Quick Start

### 1. Start infrastructure

```bash
docker compose -f docker/docker-compose.yml up -d
```

| Service | Port | Purpose |
| --- | --- | --- |
| PostgreSQL 17 + pgvector | 5432 | Primary database |
| pgAdmin 4 | 5050 | DB admin UI |
| Redis 7 | 6379 | Caching layer |
| Jaeger (OTLP gRPC) | 4317 | Trace ingest |
| Jaeger (OTLP HTTP) | 4318 | Trace ingest |
| Jaeger UI | 16686 | Trace viewer |

> `spring.docker.compose.enabled=false` — Spring auto-compose is intentionally disabled. Bring the stack up manually.

### 2. Configure environment

Create `.env` in the `agent-manager/` directory (or at the repo root):

```properties
# Database
DB_PASSWORD=changeme

# Google (Vertex AI embedding endpoint)
GOOGLE_API_KEY=...
GOOGLE_PROJECT_ID=your-gcp-project

# Spring profile override (optional)
SPRING_PROFILES_ACTIVE=default

# Third-party integrations
COMPOSIO_API_KEY=...

# Auth server (JWT issuer)
JWT_ISSUER_URI=https://your-auth-server
```

> **LLM API keys (OpenAI, Anthropic, Google) are NOT configured in `.env`.** They are entered post-boot via `POST /api/v1/provider-credentials` and stored encrypted per org. See [LLM_CONFIG.md](LLM_CONFIG.md).

### 3. Run the application

```bash
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.

At startup, `DynamicProviderInitializer` detects which LLM providers have keys configured and disables the rest. The boot banner prints a **LLM Provider Initialization Report** listing active providers.

---

## Commands

| Task | Command |
| --- | --- |
| Run the app | `./mvnw spring-boot:run` |
| Build jar | `./mvnw clean package` |
| Compile check | `./mvnw clean compile` |
| Unit tests (default) | `./mvnw test` |
| Integration tests | `./mvnw test -Dexcluded.groups= -Dtest=ClassName` |
| All tests | `./mvnw test -Dgroups=""` |
| Single test class | `./mvnw test -Dtest=AgentServiceTest` |
| Single test method | `./mvnw test -Dtest=AgentServiceTest#runsSync` |

Unit tests run by default. Integration tests are tagged `@Tag("integration")` and excluded by Surefire's `<excludedGroups>integration</excludedGroups>`. They require a live Postgres instance (Testcontainers) and a full Spring context — run them with `-Dexcluded.groups=` to override the exclusion.

Always run `./mvnw clean compile` before committing — the incremental build cache can mask overload-ambiguity errors.

---

## Package Layout

```
src/main/java/ai/operativus/agentmanager/
├── core/               Domain model — entities, DTOs, events, exceptions, SPI hooks
│   ├── entity/         JPA entities (Agent, Run, Session, Workflow, Team, ...)
│   ├── model/          DTOs and records (control/dto/ for API layer DTOs)
│   ├── registry/       Module-seam interfaces (AgentOperations, RunOperations, ...)
│   ├── spi/            Extension points (RouteSelector, JobHandler, ...)
│   ├── event/          Domain events
│   └── exception/      Typed exceptions (ApprovalRequiredException, ...)
│
├── compute/            Agent execution engine — no Spring beans cross into control/
│   ├── service/        AgentService, AgentStreamManager, RunExecutionManager, TeamOrchestrationEngine
│   ├── advisor/        Advisor middleware chain (20+ advisors — see below)
│   ├── teams/          Orchestration strategies: Sequential, Planner, Router, Swarm, Tasks
│   ├── provider/       LLM provider abstraction, DynamicProviderInitializer
│   ├── mcp/            Model Context Protocol integration
│   ├── memory/         Agentic and cultural memory subsystems
│   ├── monitoring/     Runtime health probes
│   ├── security/       Compute-layer security (PII admin controller lives here)
│   ├── tools/          AI tool implementations
│   ├── evaluation/     LLM output evaluation
│   ├── extensions/     Plugin extension system
│   └── scheduling/     Scheduled run execution
│
├── control/            REST API + persistence + platform concerns
│   ├── controller/     60+ REST controllers (one per domain area)
│   ├── repository/     Spring Data JPA repositories
│   ├── service/        Platform services: job queue, A2A, approval, FinOps, gateway
│   │   └── queue/      PersistentJobQueueService + JobHandlerRegistry
│   ├── dto/            API-layer request/response records
│   ├── a2a/            Agent-to-Agent protocol runtime
│   ├── approval/       HITL approval state machine
│   ├── finops/         Token accounting and budget enforcement
│   ├── gateway/        Outbound request gateway
│   ├── security/       JWT auth, org-scoped access
│   ├── team/           Team membership and orchestration wiring
│   └── websocket/      SSE and WebSocket streaming infrastructure
│
└── config/             App-level Spring @Configuration (Liquibase, VectorStoreConfig, ...)
```

### Module seam rule

`compute/` and `control/` must not inject each other's concrete services. All cross-module wiring goes through interfaces in `core/registry/`. When adding a cross-module feature, add or extend an interface there — not a direct service import.

---

## Advisor Chain

Every `ChatClient` call passes through a priority-ordered advisor chain assembled by `AgentClientFactory`. Advisors are applied in ascending `order` value:

| Advisor | Purpose |
| --- | --- |
| `PromptInjectionAdvisor` | Blocks prompt injection attempts |
| `PIIAnonymizationAdvisor` | **PII boundary (order 10)** — redacts PII before sending to LLM |
| `StatefulStreamingPIIAdvisor` | Output-side PII redaction for TIER_2_STRICT streaming (sliding window) |
| `ContentSafetyAdvisor` | Hard safety gate — collapses streaming to block-render |
| `HitlAdvisor` | Raises `ApprovalRequiredException` → `RunStatus.PAUSED` |
| `AdvancedRagAdvisor` | RAG retrieval, re-ranking, vector store cache |
| `AgenticMemoryAdvisor` | Short-term and long-term agentic memory |
| `CulturalMemoryAdvisor` | Cultural/org-level memory injection |
| `HallucinationDetectionAdvisor` | Output faithfulness check |
| `ModerationAdvisor` | Content moderation |
| `StructuredOutputRetryAdvisor` | Retries structured-output parsing failures |
| `CircuitBreakerAdvisor` | Per-provider circuit breaker |
| `OtlpSpanExportAdvisor` | Exports per-call spans to Jaeger |
| `AgentLoggingAdvisor` | Structured run logging |
| `ExtensionHookAdvisor` | Plugin extension hooks |

**PII boundary contract**: Any advisor at order < 10 must not echo matched values into exception messages, log statements, or network calls. Enforced by `AdvisorPiiBoundaryContractTest`.

**HITL contract**: `ToolCallingExceptionConfig` registers a `ToolExecutionExceptionProcessor` with `rethrowExceptions=List.of(ApprovalRequiredException.class)`. Without this bean, Spring AI's tool-error converter would stringify the exception as a tool result and the user would never see `PAUSED`.

---

## Orchestration Strategies

Multi-agent runs use one of five strategies, selected per team:

| Strategy | Behavior |
| --- | --- |
| `SequentialOrchestrator` | Runs members one after another; each receives the previous output |
| `PlannerOrchestrator` | LLM generates an execution plan; steps dispatched to members |
| `RouterOrchestrator` | LLM selects the single best member via structured-output `RouterDecision` |
| `SwarmOrchestrator` | Members collaborate with emergent handoffs; no fixed sequence |
| `TasksOrchestrator` | Task-queue driven; members pull and execute discrete tasks |

`TransitionValidator` enforces DAG edge contracts between steps. `TierEscalationValidator` blocks a lower-tier member from being routed a request belonging to a higher compliance tier.

---

## Dynamic Agent Selection (Universal Dispatch)

Callers can submit a run without specifying an agent:

```
POST /api/v1/runs
{ "prompt": "summarize this contract", "orgId": "..." }
```

`RoutingResolverService` applies a four-step cascade per org:

1. **defaultRouterAgentId** — explicit org-level default
2. **LLM classifier** — structured-output `RouterDecision` (embedding + prompt)
3. **Rule classifier** — JSONPath expressions on request metadata
4. **fallbackAgentId** — last-resort configured fallback

**Feature flags** (in `application.properties`):

```properties
agm.universal-dispatch.enabled=false   # enable org-level universal routing
agm.member-resolver.enabled=false      # enable team member auto-resolution
```

Routing configuration is managed via `POST/PUT /api/v1/routing-config` (per-org `OrgRoutingConfig`).

---

## API Reference

The API base is `http://localhost:8080`. All endpoints require a valid JWT bearer token unless otherwise noted.

### Agents

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/agents` | List agents (paginated) |
| `GET` | `/api/agents/{agentId}` | Get agent |
| `POST` | `/api/agents/{agentId}/runs` | Run agent (synchronous) |
| `POST` | `/api/agents/{agentId}/runs/stream` | Run agent (SSE streaming) |
| `POST` | `/api/agents/{agentId}/runs/background` | Run agent (async background) |
| `GET` | `/api/agents/{agentId}/runs/status` | Batch status for multiple runIds (`?runIds=...`) |
| `POST` | `/api/agents/{agentId}/knowledge/load` | Trigger knowledge ingestion |
| `GET/POST/DELETE` | `/api/v1/agents/{agentId}/credentials` | Per-agent LLM credentials |

Agent CRUD (create / update / delete / restore / export / import / bulk-action) is admin-only via `/api/admin/agents/**` (`AgentAdminController`, class-level `hasRole('ADMIN')` gate from PR #969).

### Runs

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/v1/runs` | List runs (paginated, tenant + ownership scoped) |
| `POST` | `/api/runs` | Universal dispatch (no agent ID required — `UniversalDispatchController`) |
| `GET` | `/api/v1/runs/{runId}` | Get run (existence-leak protected: 404 on cross-tenant) |
| `POST` | `/api/v1/runs/{runId}/cancel` | Cancel run (user-side; added PR #973) |
| `POST` | `/api/admin/agents/runs/{runId}/cancel` | Cancel run (admin-side, cross-user within org) |

HITL resume of a PAUSED run is via the approval workflow: a paused run raises `ApprovalRequiredException`, which surfaces as an `Approval` row. Resolve via `POST /api/v1/approvals/{id}` (decide) or `POST /api/v1/approvals/bulk-resolve`. The run resumes automatically once the approval is approved.

### Sessions

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/sessions` | List sessions |
| `GET` | `/api/sessions/{sessionId}` | Get session |
| `DELETE` | `/api/sessions/{sessionId}` | Delete session |
| `GET` | `/api/sessions/{sessionId}/runs` | Runs in a session |

### Workflows

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST` | `/api/v1/workflows` | List / create workflow |
| `GET/PUT/DELETE` | `/api/v1/workflows/{id}` | Get / update / delete |
| `POST` | `/api/v1/workflows/{id}/run` | Execute workflow |
| `GET/POST/DELETE` | `/api/v1/workflows/{id}/steps` | Manage steps |
| `GET/POST/DELETE` | `/api/v1/workflows/{id}/edges` | Manage DAG edges |
| `GET` | `/api/v1/workflows/{workflowId}/runs` | Run history |
| `POST` | `/api/v1/workflows/{id}/clone` | Clone workflow |

### Teams

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST` | `/api/v1/teams` | List / create team |
| `GET/PUT/DELETE` | `/api/v1/teams/{id}` | Get / update / delete |
| `POST` | `/api/v1/teams/{id}/run` | Run team |
| `GET/POST/DELETE` | `/api/v1/teams/{id}/members` | Manage members |
| `POST` | `/api/v1/teams/{id}/members/batch` | Bulk add members |
| `POST` | `/api/v1/teams/{id}/decide` | Trigger routing decision |
| `GET` | `/api/v1/teams/{id}/health` | Team health status |

### Knowledge Bases

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST` | `/api/v1/knowledge-bases` | List / create |
| `GET/PUT/DELETE` | `/api/v1/knowledge-bases/{id}` | Get / update / delete |
| `GET/POST` | `/api/v1/knowledge-bases/{id}/agents` | Associate agents |
| `POST` | `/api/v1/knowledge-bases/{id}/resolve` | Trigger retrieval resolution |

### Memory

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST` | `/api/memories` | List / add memories |
| `GET` | `/api/memories/search` | Semantic search |
| `GET` | `/api/memories/stats` | Usage statistics |
| `GET` | `/api/memories/topics` | Topic clustering |
| `POST` | `/api/memories/optimize` | LLM-based consolidation |
| `POST` | `/api/memories/cache/clear` | Clear vector cache |

### HITL Approvals

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/v1/approvals/pending` | List pending approvals (paginated) |
| `GET` | `/api/v1/approvals/manifests` | Approval manifests |
| `POST` | `/api/v1/approvals/bulk-resolve` | Bulk approve/reject |
| `GET` | `/api/v1/approvals/{id}` | Get approval |

### Schedules

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST` | `/api/v1/schedules` | List / create scheduled runs |
| `GET/PATCH/DELETE` | `/api/v1/schedules/{id}` | Get / update / delete |

### LLM Providers & Models

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST/DELETE` | `/api/v1/provider-credentials` | Manage per-org LLM API keys |
| `GET` | `/api/v1/models/catalog` | Available models across providers |
| `GET/POST/PUT` | `/api/models` | Model entity management |

### Dynamic Routing

| Method | Path | Description |
| --- | --- | --- |
| `GET/PUT/DELETE` | `/api/v1/routing-config` | Per-org routing configuration |
| `GET` | `/api/v1/admin/routing-decisions` | Routing decision audit log |
| `POST/DELETE` | `/api/v1/admin/routing-embeddings` | Manage routing embeddings |

### A2A (Agent-to-Agent)

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST/…` | `/api/v1/a2a` | A2A peer registration and health |
| `GET` | `/api/v1/health` | A2A health endpoint (peer-facing) |

### Observability & FinOps

| Method | Path | Description |
| --- | --- | --- |
| `GET/…` | `/api/v1/observability` | Traces, spans, run telemetry |
| `GET/…` | `/api/v1/observability/aggregates` | Aggregate metrics |
| `GET/…` | `/api/v1/finops` | Token usage, cost reporting, budget caps |
| `GET/…` | `/api/monitoring` | Runtime monitoring endpoints |
| `GET/…` | `/api/observability` | Additional observability endpoints |
| `GET/…` | `/api/diagnostics` | System diagnostics |

### Compliance & Security

| Method | Path | Description |
| --- | --- | --- |
| `GET/…` | `/api/compliance` | Compliance tier reporting |
| `GET/…` | `/api/v1/escalations` | Tier escalation events |
| `GET/…` | `/api/v1/observability/security-intercepts` | Security intercept log |
| `GET/POST/…` | `/api/alerts` | Alerting rules and events |
| `GET/…` | `/api/alerts/integrations` | Alert integration config |

### Evaluations

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST/…` | `/api/v1/evaluations` | Create and run evaluations |

### Extensions, Skills, Registry, MCP

| Method | Path | Description |
| --- | --- | --- |
| `GET/POST/…` | `/api/v1/extensions` | Plugin extensions |
| `GET/POST/…` | `/api/v1/skills` | Skill registry |
| `GET/POST/…` | `/api/v1/registry` | Agent registry |
| `GET/POST/…` | `/api/mcp` | Model Context Protocol config |

### Settings, Config, Auth

| Method | Path | Description |
| --- | --- | --- |
| `GET/PUT` | `/api/v1/settings` | Org-level settings |
| `GET/PUT` | `/api/config` | Runtime configuration |
| `POST/…` | `/api/auth` | Authentication endpoints |
| `GET/…` | `/api/jobs` | Background job status |
| `GET/POST/…` | `/api/tools` | Tool registry |

### Admin

| Method | Path | Description |
| --- | --- | --- |
| `GET/…` | `/api/admin/agents` | Agent admin operations |
| `GET/POST/…` | `/api/admin/users` | User management |
| `GET/…` | `/api/admin/audit-logs` | Audit log viewer |
| `GET/…` | `/api/admin/system-audit-logs` | System-level audit log |
| `GET/POST/…` | `/api/admin/retention` | Data retention policy |
| `GET/…` | `/api/admin/composio/catalog` | Composio integration catalog |
| `GET/…` | `/api/admin/composio/config-drift` | Composio config drift detection |
| `GET/…` | `/api/admin/composio/connection` | Composio connection management |
| `GET/…` | `/api/v1/admin` | Admin user/provider management |

---

## Schema Management

The database schema is owned by **Liquibase**. `spring.jpa.hibernate.ddl-auto=validate` — Hibernate validates only, never alters the schema.

Add schema changes by creating a new changelog entry in:
```
src/main/resources/db/changelog/db.changelog-master.xml
```

Never switch `ddl-auto` to `update` or `create`.

---

## Compliance Tiers

Every agent runs under one of three tiers, which gate advisor behavior:

| Tier | PII redaction | Content safety | Streaming output redaction |
| --- | --- | --- | --- |
| `TIER_1_STANDARD` | Input only | Soft gate | No output redaction |
| `TIER_2_STRICT` | Input + output | Hard gate (blocks until safe) | `StatefulStreamingPIIAdvisor` (sliding window) |
| `TIER_3_REGULATED` | Full audit trail | Hard gate + escalation | Full block-render |

Tier escalation (a lower-tier member handling a higher-tier request) is blocked by `TierEscalationValidator` at the orchestration layer.

---

## Background Jobs

New background work must be implemented as a `JobHandler` registered with `JobHandlerRegistry`. Do not use ad-hoc `@Async` methods.

Existing handlers: `AgentRunJobHandler`, `KnowledgeIngestionJobHandler`, `WorkflowExecutionJobHandler`, `WorkflowResumeJobHandler`, `BulkExportJobHandler`, `ErasureJobHandler`, `EvaluationJobHandler`, `MemoryOptimizationJobHandler`, `KnowledgeBaseDeletionJobHandler`, `AgentBulkActionJobHandler`.

---

## Configuration Reference

All tunables live in `src/main/resources/application.properties` under clearly-commented sections:

| Prefix | Controls |
| --- | --- |
| `agm.universal-dispatch.*` | Universal dispatch feature flags |
| `agm.member-resolver.*` | Team member resolution strategy |
| `agm.orchestration.*` | Concurrency caps, team size limits |
| `agm.compliance.*` | Tier defaults, PII, content safety |
| `agm.rag.*` | Retrieval strategy, chunk size, reranker |
| `agm.finops.*` | Budget caps, token accounting windows |
| `agm.a2a.*` | Peer health monitor intervals |
| `spring.ai.*` | Provider-specific model and endpoint defaults |
| `spring.datasource.*` | Database connection (DB_PASSWORD env var) |
| `spring.data.redis.*` | Redis host and port |
| `management.tracing.*` | OTLP tracing config |

Profile-specific overrides: `application-dev.properties`, `application-prod.properties`, `application-demo.properties`.

---

## API Contract Guards

Three tests enforce the REST API contract on every build:

- **`ControllerReturnTypeArchTest`** — fails if any `@*Mapping` handler returns `Map<String, Object>`, `Object`, `ResponseEntity<?>`, or other schema-erasing types.
- **`ControllerContractArchTest`** — fails if any `@RequestBody` parameter uses `Object`, `Map<String, Object>`, or raw `Map`. New endpoints must use typed records.
- **`PaginationContractTest`** — pins the `Page<T>` wire shape to `{content, totalElements, totalPages, ...}`.

Pre-existing exceptions sit on allowlists in the test classes. Adding entries requires a justification comment. Deletions ratchet the count down.

---

## Development Notes

- **`SpringAiRetryAutoConfiguration` is excluded** — the app uses `@EnableRetry` directly. Do not re-enable it.
- **Virtual threads** — `spring.threads.virtual.enabled=true`. Anything touching MDC must use Micrometer Context Propagation (already wired).
- **Streaming Gemini workaround** — `AgentStreamManager` has an `onErrorResume(NoSuchElementException.class, …)` for a Spring AI upstream bug in the Gemini finish-reason-only terminal chunk. Do not remove until upstream is fixed.
- **Provider beans may not exist** — always gate on `agent.provider.<name>.active` property or inject `Optional<…>`. Never assume a given LLM provider bean is present.
- **Ports of entry are the registries** — new features that cross `compute/` ↔ `control/` must go through `core/registry/` interfaces.
- **Every top-level class needs a Domain Responsibility / State Javadoc block** — see `AgentService` or `AgentStreamManager` for the pattern.
