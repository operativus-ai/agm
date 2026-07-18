# AGM — Agent Manager

An **agentic control plane** — a backend platform for orchestrating, governing, and operating AI agents in production. AGM abstracts away the operational complexity of building production AI systems: provider management, multi-agent coordination, compliance enforcement, run lifecycle, and observability are handled by the platform so product teams write features instead of infrastructure.

---

## Editions & License

This repository is **AGM Core**, licensed under [FSL-1.1-ALv2](LICENSE.md) (source-available; converts to Apache 2.0 two years after each release).

Our line is simple: **safety is free; proof, control, and scale are paid.**

- **Core (this repo)** ships the complete single-org platform *including every safety feature*: the full advisor safety chain (PII anonymization, content safety, tier-escalation guards), HITL approvals, budget caps, incident response (quarantine, halt-all-runs), GDPR compliance tooling, audit trails, and version rollback. Core is a real production system, not a demo.
- **Enterprise** (separate distribution, consumes Core as a library) adds fleet-scale operations: multi-org management, dynamic agent routing / universal dispatch, SLO tracking and observability aggregates, alerting integrations (webhooks, PagerDuty), FinOps analytics and cost forecasting, bulk fleet administration, and developer-experience analytics. Contact **sales@operativus.ai**.

A Core deployment never phones home and has no license checks — enterprise features simply don't exist in this codebase.

## What Core Does

AGM sits between your application and one or more LLM providers:

- **Multi-agent orchestration** — Sequential, Planner, Router, and Swarm strategies with DAG-validated workflow execution
- **Run lifecycle management** — queued background runs, streaming, pause/resume (HITL), cancellation, retry
- **Compliance tiers** — TIER_1_STANDARD / TIER_2_STRICT / TIER_3_REGULATED with PII anonymization, content safety, and tier-escalation guards
- **Provider abstraction** — OpenAI, Anthropic, Google Vertex; keys stored encrypted per org; dynamic provider initialization at boot
- **Knowledge bases** — per-agent RAG with pgvector; multi-strategy retrieval (semantic, keyword, hybrid)
- **Cost controls** — per-run token accounting, budget policies and caps, live burn-rate guard
- **Incident response** — agent quarantine, emergency halt-all-runs, immutable audit trails, config version rollback
- **A2A** — Agent-to-Agent protocol for peer discovery, health monitoring, and cross-instance delegation
- **Full observability plumbing** — OTLP tracing (Jaeger), Micrometer metrics, structured logging with MDC propagation

---

## Repository Layout

```
agent-manager/          Spring Boot 4 backend (Java 25) — Maven artifact ai.operativus:agm-core
agent-manager-ui/       React frontend — npm package @operativus-ai/ui-core
docs/                   Architecture docs and feature deep-dives
clients/                Reference client implementations
deploy/                 Production docker-compose stack, backup/restore scripts, runbook
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25, virtual threads |
| Framework | Spring Boot 4, Spring Modulith |
| AI | Spring AI |
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

# Optional: override active profile
SPRING_PROFILES_ACTIVE=default
```

> **Per-org LLM keys** are configured via `POST /api/v1/provider-credentials`, not `.env`. The `.env` keys are for local development only.

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

For a production-shaped deployment (Caddy TLS, backup/restore scripts, operator runbook) see [`deploy/README.md`](deploy/README.md).

---

## Backend Commands

All commands use the Maven wrapper from the `agent-manager/` directory.

| Task | Command |
|---|---|
| Run the app | `./mvnw spring-boot:run` |
| Build jar | `./mvnw clean package` |
| Unit tests (default) | `./mvnw test` |
| All tests incl. integration | `./mvnw test -Dexcluded.groups=` |
| Single test class | `./mvnw test -Dtest=AgentServiceTest` |
| Compile check | `./mvnw clean compile` |

> Integration tests use Testcontainers (Docker required) and are tagged `@Tag("integration")`. Surefire excludes them by default.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    REST API (port 8080)               │
│                  control/controller/                  │
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
  │  Teams          │   │  A2A, WebSocket     │
  │  LLM providers  │   │  Compliance, Sec    │
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
- **`control/`** — REST API, JPA repositories, background job queue (`PersistentJobQueueService`), A2A protocol, WebSocket streaming, compliance
- **`core/`** — domain model: entities, registry interfaces, SPI hooks, exceptions, events

### Advisor chain execution order

Advisors wrap each `ChatClient` call in priority order. Critical boundaries:

- **Order 10** — PII anonymization boundary. Advisors at order < 10 must not echo user input into logs, exceptions, or network calls.
- **TIER_2_STRICT** streaming — output-side PII redaction via `StatefulStreamingPIIAdvisor` (sliding window).
- **HITL** — `ApprovalRequiredException` is explicitly re-thrown (not stringified) so `RunStatus.PAUSED` surfaces to the caller.

### Extension points

Core is designed to be extended without forking:

- **`core/spi/`** — service-provider interfaces (custom tools, agent lifecycle hooks, workflow step executors, PII scrubbers, advisor contributors) discovered via Spring beans or `META-INF/services`
- **`db/changelog/ext/`** — an add-on jar may ship additional Liquibase changesets at this classpath path (add-only with respect to Core tables)
- **UI manifest seams** — the frontend merges edition route/nav/widget manifests at build time via `@ee/*` aliases (Core ships empty stubs)

---

## Configuration

All tunables live in `agent-manager/src/main/resources/application.properties` under clearly-commented sections. Key groups:

| Section | Controls |
|---|---|
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

Never edit an already-merged changeset — add a new one.

---

## Development Notes

- `spring.docker.compose.enabled=false` — Spring auto-compose is off; bring infrastructure up manually with `docker compose`.
- Virtual threads are enabled; anything touching MDC must use Micrometer Context Propagation.
- Always run `./mvnw clean compile` before committing; the incremental cache can mask overload-ambiguity errors.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) (DCO sign-off required). Security reports: [SECURITY.md](SECURITY.md).
