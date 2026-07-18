# Agent Manager

An enterprise-grade **agentic control plane** — a backend platform for orchestrating, governing, and operating AI agents at scale. Agent Manager abstracts away the operational complexity of building production AI systems: provider management, multi-agent coordination, compliance enforcement, run lifecycle, cost accountability, and observability are handled by the platform so product teams write features instead of infrastructure.

---

## What It Is

Agent Manager is the operational layer that sits between your application and one or more LLM providers. It does not replace your prompts or your business logic — it provides the infrastructure every production AI system eventually has to build:

- **Dynamic agent selection** — callers POST a prompt with no agent specified; the platform routes to the right specialist via LLM classifier, rule engine, or configured fallback
- **Multi-agent orchestration** — Sequential, Planner, Router, and Swarm strategies with DAG-validated workflow execution
- **Run lifecycle management** — queued background runs, streaming, pause/resume (HITL), cancellation, retry
- **Compliance tiers** — TIER_1_STANDARD / TIER_2_STRICT / TIER_3_REGULATED with PII anonymization, content safety, and tier-escalation guards
- **Provider abstraction** — OpenAI, Anthropic, Google Vertex; keys stored encrypted per org; dynamic provider initialization at boot
- **Knowledge bases** — per-agent RAG with pgvector; multi-strategy retrieval (semantic, keyword, hybrid)
- **Cost and FinOps** — per-run token accounting, budget caps, usage reporting
- **A2A** — Agent-to-Agent protocol for peer discovery, health monitoring, and cross-instance delegation
- **Full observability** — OTLP tracing (Jaeger), Micrometer metrics, structured logging with MDC propagation

---

## Repository Layout

```
agent-manager/          Spring Boot 4 backend (Java 25)
agent-manager-ui/       React frontend (TanStack Query/Table, XYFlow, Tailwind)
docs/                   Architecture docs and white papers
  features/             Feature deep-dives (.md + .docx)
clients/                Reference client implementations
  demo-java-agent/      Java client demonstrating the SDK
  demo-react-ui/        React client demonstrating the REST API
scripts/                Dev and demo utility scripts
a2a-java/               A2A protocol Java library
agno/                   Agno integration
firecrawl-local/        Firecrawl local deployment
```

> `website/` is a separate marketing site — excluded from backend development scope.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25, virtual threads |
| Framework | Spring Boot 4.0.0-SNAPSHOT, Spring Modulith 1.4.0-SNAPSHOT |
| AI | Spring AI 2.0.0-SNAPSHOT |
| Database | PostgreSQL 17 + pgvector |
| Cache | Redis |
| Tracing | Jaeger (OTLP) |
| Schema | Liquibase (`db/changelog/db.changelog-master.xml`) |
| Frontend | React, TanStack Query/Table, XYFlow, Monaco Editor, Tailwind CSS |

---

## Prerequisites

- **Java 25** (JDK; use `JAVA_HOME` or a version manager)
- **Docker + Docker Compose** (for the local infrastructure stack)
- **Node.js 20+** and **npm** (for the frontend)
- **Maven** — use the included wrapper (`./mvnw`), not a system install

---

## Quick Start

### 1. Start infrastructure

```bash
cd agent-manager
docker compose -f docker/docker-compose.yml up -d
```

This starts:
- PostgreSQL 17 + pgvector (port 5432)
- pgAdmin 4 (port 5050)
- Redis (port 6379)
- Jaeger all-in-one / OTLP collector (ports 4317, 16686)

### 2. Configure environment

Copy the template and fill in your values:

```bash
cp .env.example .env   # or create .env manually at the repo root
```

Required keys in `.env`:

```properties
# Database
DB_PASSWORD=your-postgres-password

# LLM providers — configure at least one
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
GOOGLE_API_KEY=...
GOOGLE_PROJECT_ID=your-gcp-project   # Vertex AI embedding endpoint

# Third-party integrations
COMPOSIO_API_KEY=...

# Optional: override active profile
SPRING_PROFILES_ACTIVE=default
```

> **Per-org LLM keys** (for multi-tenant use) are configured via `POST /api/v1/provider-credentials`, not `.env`. The `.env` keys are for local development only.

### 3. Run the backend

```bash
cd agent-manager
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.

Providers with no API key are automatically disabled at startup (see `DynamicProviderInitializer`). The boot banner lists which LLM providers initialized successfully.

### 4. Run the frontend

```bash
cd agent-manager-ui
npm install
npm run dev
```

The UI is available at `http://localhost:5173` (Vite default).

---

## Backend Commands

All commands use the Maven wrapper from the `agent-manager/` directory.

| Task | Command |
|---|---|
| Run the app | `./mvnw spring-boot:run` |
| Build jar | `./mvnw clean package` |
| Unit tests (default) | `./mvnw test` |
| Integration tests | `./mvnw test -Dexcluded.groups= -Dtest=ClassName` |
| All tests | `./mvnw test -Dgroups=""` |
| Single test class | `./mvnw test -Dtest=AgentServiceTest` |
| Single test method | `./mvnw test -Dtest=AgentServiceTest#runsSync` |
| Compile check | `./mvnw clean compile` |

> Integration tests require a live Postgres instance and are tagged `@Tag("integration")`. Surefire excludes them by default.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    REST API (port 8080)               │
│              control/controller/  (60+ endpoints)    │
└───────────────────────┬─────────────────────────────┘
                        │
            ┌───────────▼────────────┐
            │   core/registry/       │  ← module seams
            │  (AgentOperations,     │
            │   RunOperations, ...)  │
            └───────────┬────────────┘
              ┌─────────┴──────────┐
              ▼                    ▼
  ┌─────────────────┐   ┌─────────────────────┐
  │   compute/      │   │   control/          │
  │  AgentService   │   │  Persistence (JPA)  │
  │  Advisor chain  │   │  Job queue          │
  │  Teams/routing  │   │  A2A, WebSocket     │
  │  LLM providers  │   │  FinOps, Security   │
  └────────┬────────┘   └─────────────────────┘
           │
   ┌───────▼────────┐
   │  Spring AI     │
   │  ChatClient    │
   └───────┬────────┘
           │
   ┌───────▼────────────────────┐
   │  LLM Providers             │
   │  OpenAI / Anthropic /      │
   │  Google Vertex             │
   └────────────────────────────┘
```

### Key packages

- **`compute/`** — agent execution engine: advisor chain (RAG, PII, HITL, safety, circuit breaker, OTLP), orchestration strategies (Sequential, Planner, Router, Swarm), provider abstraction, MCP tool integration
- **`control/`** — REST API, JPA repositories, background job queue (`PersistentJobQueueService`), A2A protocol, WebSocket streaming, compliance, FinOps
- **`core/`** — domain model: entities, DTOs, registry interfaces, SPI hooks, exceptions, events

### Advisor chain execution order

Advisors wrap each `ChatClient` call in priority order. Critical boundaries:

- **Order 10** — PII anonymization boundary. Advisors at order < 10 must not echo user input into logs, exceptions, or network calls.
- **TIER_2_STRICT** streaming — output-side PII redaction via `StatefulStreamingPIIAdvisor` (sliding window).
- **HITL** — `ApprovalRequiredException` is explicitly re-thrown (not stringified) so `RunStatus.PAUSED` surfaces to the caller.

### Dynamic agent selection

Callers can omit the agent ID entirely:

```
POST /api/runs
{ "prompt": "summarize this contract", "orgId": "..." }
```

`RoutingResolverService` applies a four-step cascade:
1. `defaultRouterAgentId` (org config)
2. LLM classifier (structured-output `RouterDecision`)
3. Rule classifier (JSONPath expressions)
4. `fallbackAgentId`

Enable via `agm.universal-dispatch.enabled=true` in `application.properties`.

---

## Configuration

All tunables live in `agent-manager/src/main/resources/application.properties` under clearly-commented sections. Key groups:

| Section | Controls |
|---|---|
| `agm.universal-dispatch.*` | Org-level request routing without agent ID |
| `agm.member-resolver.*` | Team member resolution strategy |
| `agm.orchestration.*` | Concurrency caps, team size limits |
| `agm.compliance.*` | Tier defaults, PII, content safety |
| `agm.rag.*` | Retrieval strategy, chunk size, reranker |
| `agm.finops.*` | Budget caps, token accounting |
| `agm.a2a.*` | Peer health monitor intervals |
| `spring.ai.*` | Provider-specific model defaults |

---

## Schema Management

The database schema is owned by **Liquibase**. `spring.jpa.hibernate.ddl-auto=validate` — Hibernate validates only, never alters.

Add schema changes by creating a new changelog entry in:
```
agent-manager/src/main/resources/db/changelog/db.changelog-master.xml
```

---

## Documentation

Extended documentation lives in [`docs/features/`](docs/features/):

| Document | Description |
|---|---|
| `agm-features.md` | Full feature reference |
| `agm-features-wp.md` | Enterprise white paper (CTO/VP audience) |
| `agm-dynamic-agent-routing.md` | Dynamic agent routing deep-dive |
| `agm-architecture.md` | Architectural overview |
| `agm-cto-pitch.md` | Executive pitch deck narrative |
| `agm-developer-deep-dive.md` | Developer implementation guide |
| `agm-technical.md` | Technical reference |

All `.md` files have a corresponding `.docx` export.

---

## Development Notes

- `spring.docker.compose.enabled=false` — Spring auto-compose is off; bring infrastructure up manually with `docker compose`.
- `SpringAiRetryAutoConfiguration` is excluded; the app uses `@EnableRetry` directly.
- Virtual threads are enabled; anything touching MDC must use Micrometer Context Propagation.
- The `website/` directory is a separate project — do not include it in backend audits or PRs.
- Always run `./mvnw clean compile` before committing; the incremental cache can mask overload-ambiguity errors.

---

## Contributing

1. Create a branch per feature/fix, stacked on `main`
2. Run `./mvnw clean compile` and `./mvnw test` before pushing
3. Follow the Domain Responsibility / State Javadoc pattern for new top-level classes
4. New background work → implement `JobHandler` + register it; do not use ad-hoc `@Async` methods
5. New cross-module features → add or extend an interface in `core/registry/`, not a direct service injection
