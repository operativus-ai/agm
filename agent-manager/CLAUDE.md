# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Stack

- **Java 25**, Spring Boot **4.0.7** (GA), Spring AI **2.0.0** (GA), Spring Modulith **2.0.7** (GA). Pinned off SNAPSHOT for reproducible builds — bump deliberately, not by floating.
- Repos `spring-milestones` and `spring-snapshots` are still declared in `pom.xml` (kept for transitive milestone deps and easy version bumps). Do not remove them.
- PostgreSQL + pgvector is the system of record; schema is owned by **Liquibase** (`db/changelog/db.changelog-master.xml`). `spring.jpa.hibernate.ddl-auto=validate` — never switch to `update`/`create`.
- Virtual threads are enabled (`spring.threads.virtual.enabled=true`). Anything touching MDC/context must respect Micrometer Context Propagation (already wired in `application.properties`).

## Commands

Use the Maven wrapper (`./mvnw`), not a system Maven.

| Task | Command |
|------|---------|
| Run the app | `./mvnw spring-boot:run` |
| Build jar | `./mvnw clean package` |
| Unit tests only (default) | `./mvnw test` |
| Integration tests | `./mvnw test -Dgroups=integration` |
| All tests | `./mvnw test -Dgroups=""` (overrides the `integration` exclude) |
| Single test class | `./mvnw test -Dtest=AgentServiceTest` |
| Single test method | `./mvnw test -Dtest=AgentServiceTest#runsSync` |

Surefire is configured with `<excludedGroups>integration</excludedGroups>` — any test tagged `@Tag("integration")` is skipped by `mvn test`. Integration tests assume a live Postgres; they won't run standalone without one.

`docker-compose up -d` brings up Postgres+pgvector (needed before the app boots). Note `spring.docker.compose.enabled=false` is set intentionally — Spring's auto-compose is off because of working-directory issues across IDE/Maven; bring the stack up manually.

Configure infrastructure secrets via `.env` in the repo root (`spring.config.import=optional:file:.env[.properties]`). Only non-LLM secrets belong here: `DB_PASSWORD`, `GOOGLE_PROJECT_ID` (used by the Vertex embedding endpoint), `SPRING_PROFILES_ACTIVE`. **Per-(org, provider) LLM API keys are configured via the admin endpoint `POST /api/v1/provider-credentials`** and stored encrypted in the `provider_credentials` table. A per-model override on `ModelEntity.apiKey` takes precedence when set. There is NO `.env` / Spring property / OS environment fallback for LLM keys — see `AbstractDynamicModelProvider.resolveApiKey`.

## Architecture

Top-level packages under `ai.operativus.agentmanager` express the domain split. Treat them as boundaries:

- **`core/`** — domain model. Entities (`entity/`), DTOs/records (`model/`), registry interfaces (`registry/`), SPI hooks (`spi/`), exceptions, events, callbacks. No Spring beans here other than JPA entities. **The interfaces in `core/registry/` (`AgentOperations`, `RunOperations`, `ModelOperations`, `KnowledgeIngestionOperations`, …) are the seams between modules** — `compute` and `control` depend on these abstractions, not on each other's concrete services.
- **`compute/`** — agent runtime. The LLM execution path lives here:
  - `service/AgentService` — single-agent orchestration engine. Delegates streaming to `AgentStreamManager`, team runs to `TeamOrchestrationEngine`, background runs to `RunExecutionManager`.
  - `service/AgentClientFactory` — builds the Spring AI `ChatClient` with the advisor chain attached per request.
  - `advisor/` — the heart of the execution pipeline. Advisors are middleware around the `ChatClient` (RAG, HITL, PII anonymization, prompt-injection, moderation, hallucination detection, OTLP span export, agentic/cultural memory, circuit breaker, structured-output retry). Order matters; inspect `AgentClientFactory` before reordering.
    - **PII boundary at order 10.** `PIIAnonymizationAdvisor.adviseCall` mutates `request.prompt()` to substitute redacted text. Any advisor that reads `request.prompt().getContents()` MUST run at order ≥ 10 — otherwise it sees raw user PII. Advisors running before order 10 must be defensive (regex/structural checks that do NOT echo the matched value into exception messages, log statements, or network calls). The contract is enforced by `AdvisorPiiBoundaryContractTest` in `src/test/java/ai/operativus/agentmanager/compute/advisor/`. See `.claude/reports/audit-advisor-chain-2026-05-04.md` (audit findings F11/F12) for the historical regressions this test guards against.
    - **Streaming output safety is tier-keyed.** `ContentSafetyAdvisor.adviseStream` is a hard gate (`collectList().flatMapMany`) — streaming UX collapses to block-render in exchange for the safety guarantee. `PIIAnonymizationAdvisor.adviseStream` redacts the request only; output-side redaction for `TIER_2_STRICT` agents lives in `StatefulStreamingPIIAdvisor` (sliding-window). `TIER_1_STANDARD` streaming output passes through unredacted by design — closing that gap is a UX-vs-safety product call.
    - **HITL exceptions are propagated, not stringified.** `ToolCallingExceptionConfig` registers a `ToolExecutionExceptionProcessor` bean with `rethrowExceptions=List.of(ApprovalRequiredException.class)` so Spring AI's tool-error conversion does NOT swallow the HITL signal. Without that bean, `ApprovalRequiredException.getMessage()` would be fed back to the LLM as a tool result and the user would never see `RunStatus.PAUSED`.
  - `teams/` — multi-agent orchestration strategies (`PlannerOrchestrator`, `RouterOrchestrator`, `SequentialOrchestrator`, `SwarmOrchestrator`) behind `OrchestrationStrategy`.
  - `provider/`, `mcp/`, `memory/`, `monitoring/`, `scheduling/`, `security/`, `tools/`, `evaluation/`, `extensions/` — supporting subsystems.
  - `DynamicProviderInitializer` (see below).
- **`control/`** — API + persistence + cross-cutting platform concerns.
  - `controller/` — REST controllers (Agents, Auth, Model, Jobs, Workflows, Schedules, A2A, Teams, KnowledgeBase, AgentCredential, Config, Monitoring, Tool, Evaluation, AgentAdmin, Memory, Registry, Compliance, …). One small controller lives at `compute/api/PiiAdminController.java`.
  - `service/queue/` — `PersistentJobQueueService` + `JobHandlerRegistry` drive the background-job queue. Handlers (`AgentRunJobHandler`, `KnowledgeIngestionJobHandler`, `WorkflowExecutionJobHandler`, `WorkflowResumeJobHandler`, `BulkExport…`, `Erasure…`, `Evaluation…`, `MemoryOptimization…`, `KnowledgeBaseDeletion…`, `AgentBulkAction…`) implement `JobHandler` and are registered via the registry. Add new background work by adding a handler + registering it, not by spinning up ad-hoc `@Async` methods.
  - `repository/`, `security/`, `a2a/`, `approval/`, `finops/`, `gateway/`, `websocket/`, `team/`.
- **`config/`** — app-level Spring `@Configuration` (Liquibase, `VectorStoreConfig`, `PiiVectorStoreConfig`).

### Bootstrap quirks to know

`AgentmanagerApplication` bootstraps via `SpringApplicationBuilder` (not `SpringApplication.run`) specifically to register `DynamicProviderInitializer` as an `ApplicationContextInitializer`. That initializer runs **before** bean creation and:

- Detects which of OpenAI / Anthropic / Google have real API keys set.
- For each missing key, it sets `spring.ai.<provider>.enabled=false` (plus chat/embedding/image/audio flags) and injects a dummy key (`dummy-key-to-pass-validation`) to satisfy Spring AI's eager validation.
- Sets `agent.provider.<name>.active` flags that downstream code reads.

Consequence: in code, never assume a given LLM provider bean exists — always gate on the `agent.provider.<name>.active` property or inject `Optional<…>`. The startup banner ("LLM Provider Initialization Report") prints via `System.out` because SLF4J isn't wired yet at initializer time — keep it that way.

`SpringAiRetryAutoConfiguration` is excluded on the `@SpringBootApplication` in favor of Spring's `@EnableRetry` on the main class. Don't re-enable it.

### Streaming quirk

`AgentStreamManager.stream(...)` has an `onErrorResume(NoSuchElementException.class, …)` around the Gemini streaming path. This is a deliberate workaround for a Spring AI upstream bug where `responseCandidateToGeneration()` calls `Optional.get()` on the terminal finish-reason-only chunk. Don't remove it until Spring AI fixes it upstream — comments in the file and in `application.properties` both track the reason.

## Conventions worth knowing

- **Ports of entry are the registries, not the services.** When wiring a new feature across `compute` ↔ `control`, add (or extend) an interface in `core/registry/` and depend on that, rather than injecting a concrete service across module boundaries.
- **Every class has a Domain Responsibility / State Javadoc block.** When adding new top-level classes in `compute/` or `control/`, follow the existing pattern (see `AgentService`, `AgentStreamManager`, `DynamicProviderInitializer`).
- **Property externalization.** Tunables (scheduler intervals, rate limits, retention windows, orchestration caps, RAG reranker config, guardrails, model-resolution defaults) live in `application.properties` under clearly-commented sections. Prefer adding a property over a hard-coded constant — the comments in that file explain why (SRE tunability, compliance, cost control).
- **No `templates/` or `static/` UI assets** ship from this backend. The React UI lives in a sibling `agent-manager-ui/` project.

### API contract — controller return types

Three forward guards lock the contract surface against schema drift. Reviewer rule: if a new mapping handler doesn't compile under these guards, fix the handler (don't extend the allowlist without a justification in the PR description).

- **Return-type arch test** — `ControllerReturnTypeArchTest` (in `src/test/java/.../arch/`) is a pure-classpath unit test (~0.5s, runs every `./mvnw test`). It fails the build when any `@*Mapping` handler returns a schema-erasing type:
  - `Map<String, Object>` / `Map<String, ?>` / `Object`
  - `ResponseEntity<?>` / `ResponseEntity<Map<String, Object>>`
  - `List<Map<String, Object>>` (one-level descent)
  - Pre-existing violators sit on `ALLOWLIST` tagged `// TODO: Phase 3 / future`. Adding entries requires PR-description justification; deletions are how the count ratchets down.
  - `Map<String, String>`, `Map<String, Integer>`, `Map<JobStatus, Long>` are NOT flagged — value type is bound. These are "drift-prone" not "no-schema". Promote when a consumer materializes; do not promote enum-keyed maps (wrapper-record proliferation, see `BulkResolveResponse` / `BulkActionResponse` / `WorkflowExecutionResponse` for the right shape).
- **Pagination wire shape pin** — `spring.data.web.pageable.serialization-mode=direct` in `application.properties` locks the JSON shape of `Page<T>` returns to `{content, totalElements, totalPages, ...}`. `PaginationContractTest` asserts this directly via `/api/v1/approvals/pending` so a future Spring default flip surfaces as a test failure.
- **FE forward guard** — sibling `agent-manager-ui/eslint.config.js` has a `no-restricted-syntax` rule blocking new untyped `ApiClient.{get,post,put,delete,patch}(...)` calls. Every FE call that crosses the wire must declare `<T>`.

**`@RequestBody` arch test** — `ControllerContractArchTest` (sibling to `ControllerReturnTypeArchTest`) also enforces inbound body typing. It fails the build when any `@RequestBody` parameter is:
  - `Object` — no schema at all
  - `Map<String, Object>` / `Map<String, ?>` — no schema, `@Valid` cannot apply
  - `Map<String, T>` for any value type — typed-but-loose, prompt to promote to a record
  - Raw `Map` — no schema
  - Documented exceptions sit on `REQUEST_BODY_ALLOWLIST` (e.g., `SettingsController.updateSettings` — keys are genuinely dynamic per its doc-block). Stale allowlist entries (entries no longer matching any handler) also fail the test, so the list ratchets down with the codebase.

Reviewer rule for new endpoints: `@RequestBody` should always be a typed record. If the key space is genuinely dynamic, document the rationale in the controller's javadoc AND add the entry to `REQUEST_BODY_ALLOWLIST` in the same PR.
