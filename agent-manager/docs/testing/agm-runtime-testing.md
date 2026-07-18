# Agent Manager — Runtime Testing Matrix

> Canonical test plan for **black-box / runtime** coverage of the Spring Boot backend. Every case in this document is intended to be executed against a *real* application context booted via `BaseIntegrationTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + pgvector Testcontainer + real Liquibase schema + real Spring Security + real advisor chain), not against mocks.
>
> Scope is derived from the **UI navigation** in `agent-manager-ui/src/app/router.tsx` — one category per top-level route — plus the cross-cutting runtime concerns that those routes depend on but don't surface directly.

---

## 1. Harness contract

All runtime tests extend `com.operativus.agentmanager.integration.BaseIntegrationTest` and are tagged `@Tag("integration")`. They are excluded from `./mvnw test` and run only via `./mvnw test -Dgroups=integration`.

**Rules that apply to every category below:**
- **HTTP, not services.** Hit the controller over `TestRestTemplate` on the random port. Never inject a service and call it directly — that bypasses the filter chain, advisors, rate limiter, tenant filter, and JSON (de)serialization.
- **Seed via API when possible.** Use `/api/auth/register` + `/api/auth/login` for principals; use the real create-endpoints for agents, teams, workflows, schedules, etc. Fall back to `JdbcTemplate` only for rows that have no public endpoint (model keys, audit stubs).
- **Verify three surfaces per scenario:**
  1. **HTTP response** — status, body, headers.
  2. **Persisted state** — JPA row, vector store entry, job queue row.
  3. **Side effects** — emitted events, enqueued jobs, advisor log rows, metric counters, SSE event frames.
- **Async.** `RunExecutionManager`, `PersistentJobQueueService`, `AgentStreamManager`, and every `@Scheduled`/`@Async` path commits on its own thread. Never rely on `@Transactional` rollback; use `Awaitility` for eventual consistency and let `BaseIntegrationTest#truncateDatabase()` handle cleanup.
- **LLM calls must be stubbed.** Tests that traverse the advisor chain or exercise an orchestrator must provide a fake `ChatModel` (or WireMock the OpenAI base URL). Real outbound calls are a bug in the test, not a feature — `application-test.properties` intentionally sets fake-but-non-sentinel keys to force registration of a stubbable bean.

---

## 2. Test category index (UI-nav driven)

| # | UI route | Backend surface | Section |
|---|----------|-----------------|---------|
| 1 | `/login`, `/register` | `AuthController` + JWT filter chain | [§3](#3-authentication--session-bootstrap) |
| 2 | `/` (Dashboard) | `MonitoringController`, aggregated GETs | [§4](#4-dashboard) |
| 3 | `/agents`, `/agents/new` | `AgentsController`, `AgentAdminController`, `AgentCredentialController` | [§5](#5-agents) |
| 4 | `/chat`, `/chat/:agentId[/:sessionId]` | `AgentsController` run/stream, `SessionService`, `AgentStreamManager` | [§6](#6-chat--streaming-runs) |
| 5 | `/knowledge` | `KnowledgeBaseController`, `KnowledgeIngestionService`, pgvector | [§7](#7-knowledge-bases--rag) |
| 6 | `/evaluations` | `EvaluationController`, `EvaluationRunJobHandler` | [§8](#8-evaluations) |
| 7 | `/teams`, `/teams/:id` | `TeamsController`, `TeamOrchestrationEngine`, orchestrators | [§9](#9-teams--multi-agent-orchestration) |
| 8 | `/workflows`, `/workflows/:id[/live]` | `WorkflowsController`, `WorkflowService`, `WorkflowExecutionJobHandler`, `WorkflowResumeJobHandler` | [§10](#10-workflows) |
| 9 | `/approvals` | `ApprovalService`, HITL advisor, `ApprovalsController` | [§11](#11-approvals--hitl) |
| 10 | `/schedules`, `/schedules/:id/history` | `SchedulesController`, `ScheduleService`, `ScheduleExecutionPoller` | [§12](#12-schedules) |
| 11 | `/memory` | `MemoryController`, `MemoryService`, `MemoryConsolidationWorker`, agentic memory advisor | [§13](#13-memory) |
| 12 | `/sessions`, `/sessions/:id` | `SessionService`, `PersistentChatMemory`, `SessionSummarizationService` | [§14](#14-sessions) |
| 13 | `/registry` | `RegistryController`, `DatabaseAgentRegistry` | [§15](#15-registry) |
| 14 | `/extensions` | Extensions config controller + `ExtensionHookAdvisor` + SPI | [§16](#16-extensions) |
| 15 | `/mcp` | MCP admin controller, `McpConnectionPool` | [§17](#17-mcp-servers) |
| 16 | `/models` | `ModelController`, `ModelService`, `ModelApiKeyMigrationService`, `DynamicProviderInitializer` | [§18](#18-models) |
| 17 | `/security` | `ComplianceController`, `SafetyService`, PII stack, prompt-injection | [§19](#19-security--safety) |
| 18 | `/observability` | `MonitoringController`, OTLP advisor, `GenAiMetricsAdvisor`, `SloTrackingService` | [§20](#20-observability) |
| 19 | `/finops` | `finops/` stack, `FinOpsObservedEmbeddingModel`, `TelemetryService` | [§21](#21-finops) |
| 20 | `/a2a` | `A2AController`, `PeerHealthMonitor`, a2a encryption | [§22](#22-a2a-mesh) |
| 21 | `/admin/users` | `UserAdminService`, admin fields, RBAC | [§23](#23-user-administration) |
| 22 | `/admin/audit-logs` | `AuditLogService`, audit log filter chain | [§24](#24-audit-logs) |
| 23 | `/admin/alert-integrations` | `AlertingService`, `AlertIntegrationService` | [§25](#25-alerts--integrations) |
| 24 | `/settings` | `ConfigController`, `SettingsService`, global_settings SSOT | [§26](#26-settings) |
| — | (cross-cutting) | Multi-tenancy, rate limit, job queue, schedulers, virtual threads, circuit breaker, data retention, erasure, sandbox | [§27](#27-cross-cutting-runtime-concerns) |

---

## 3. Authentication & session bootstrap

**Surface:** `AuthController` (`/api/auth/register`, `/api/auth/login`), JWT filter chain, `UserRepository`, Spring Security.

**Test cases:**
1. Register new user → 200 + user row persists with BCrypt hash (not plaintext).
2. Register duplicate username/email → 400/409, no second row.
3. Register with invalid role name → 400, no row.
4. Login with correct credentials → 200 + JWT in `token`; claims contain userId, orgId, roles.
5. Login with wrong password → 401, no token; AuthN failure is recorded (audit log row).
6. Login for disabled/locked user → 401 with distinct error code.
7. Expired token on protected endpoint → 401, body matches documented shape.
8. Malformed `Authorization` header → 401; request never reaches controller (assert via absence of audit log create-row).
9. Valid token but revoked session → 401 (if revocation is implemented).
10. `/api/*` endpoint without token → 401; public endpoints (`/api/auth/*`, actuator health) still 200.
11. Token signed with wrong key → 401.
12. Concurrent login from same user issues independent tokens — both valid simultaneously.

---

## 4. Dashboard

**Surface:** Aggregated GETs feeding `DashboardPage`; primarily `MonitoringController` + agent/run counters.

**Test cases:**
1. Empty org (fresh truncate) → dashboard counts all zero, no 500s, response shape matches contract.
2. Seed 3 agents + 2 running runs + 1 failed run → counts reflect reality within the authenticated orgId only.
3. Dashboard is org-scoped: user in org A sees zero counts for agents created under org B (seed cross-tenant state, assert isolation).
4. Running-runs widget includes only `RunStatus.RUNNING`/`QUEUED`, not `COMPLETED`/`FAILED`.
5. Response caches invalidate after mutation (POST run → immediate re-GET sees new count; verify via `@EnableCaching` behavior with real cache).

---

## 5. Agents

**Surface:** `AgentsController` (`/api/agents` or `/api/v1/agents`), `AgentAdminController`, `AgentCredentialController`, `DatabaseAgentRegistry`, `AgentService`.

**Test cases:**
1. Create agent with required fields → 201 + DB row + registry event loaded into `DatabaseAgentRegistry` cache.
2. Create with missing `modelId` → 400.
3. Create referencing a non-existent model → 400 (not 500, not NPE).
4. Create referencing a disabled provider (no real API key) → 400 with clear provider-disabled error; DB row is NOT created.
5. List agents: org-scoped; user in orgA can't see orgB rows.
6. Get by id: 200 for own; 404 (not 403) for other-org id to avoid leaking existence.
7. Update agent → version increments per `009-agent-versioning-and-rbac.sql`; previous version retrievable.
8. Soft-delete agent → disappears from list, direct GET returns 404, but FK-dependent data (runs, sessions) remains.
9. Bulk action via `AgentBulkActionJobHandler` → returns jobId, job transitions QUEUED→RUNNING→COMPLETED, results mutation visible.
10. Credential rotation: POST new key → old key unusable, new key resolves on next run.
11. RBAC: non-admin user can create own agents but cannot edit org-wide templates (if RBAC matrix requires).
12. Load knowledge on agent: `POST /api/agents/{id}/knowledge/load` enqueues `KnowledgeIngestionJobHandler`, job completes, vector rows appear, agent references them on next run.

---

## 6. Chat & streaming runs

**Surface:** `AgentsController` run endpoints (`/{id}/runs`, `/{id}/runs/stream`, `/{id}/runs/background`, `/runs/{id}/continue`), `AgentService`, `AgentStreamManager`, `RunExecutionManager`, `PersistentChatMemory`.

**Test cases:**
1. Sync run (`POST /runs`) with fake ChatModel returning canned text → 200, `RunResponse` matches, run row has `COMPLETED`, session has 2 messages persisted.
2. Sync run against disabled provider → 400 (not 500).
3. Sync run with unauthorized agent id (other org) → 404.
4. Streaming run (`POST /runs/stream`) → SSE frames in order: `START`, 1..N `CONTENT` with reasoning + content deltas, `STOP`; run row finalized to COMPLETED after last frame.
5. Streaming run where fake model errors mid-stream → SSE terminates cleanly, run row marked FAILED with error classified by `AgentErrorClassifier`.
6. Streaming run where Gemini model returns finish-reason-only last chunk → `NoSuchElementException` workaround fires; stream completes gracefully (do not remove the `onErrorResume` block without covering this).
7. Background run (`POST /runs/background`) → returns runId immediately (< 200ms), status becomes `RUNNING` then `COMPLETED`; GET status returns terminal state.
8. Background run status polling by unauthorized user → 404.
9. Session affinity: consecutive runs with same `sessionId` append to the same session; short-term memory (window) is applied; `SessionSummarizationService` fires when window exceeds threshold.
10. Session handoff: `HandOffTool` invocation transitions to a different agent; transcript remains coherent.
11. Generate-followups flag: true → followups in final event payload; false → absent.
12. Cancel background run → status transitions to `CANCELLED`; in-flight tool calls are interrupted (or at least do not write further state).
13. Tool call resulting in `RequiresConfirmation` pauses the run with `RequiredActionType.HUMAN_APPROVAL`; `POST /runs/{id}/continue` resumes with approved output.
14. Tool call that throws → run does not crash; error surfaces as a `ToolCallDTO` error record.
15. Rate limit: rapid-fire 100 runs from one user → some return 429 after the per-user-per-second threshold.
16. Virtual-thread MDC: concurrent runs from different users do not cross-contaminate MDC (assert by inspecting logs if test log capture is wired, or by checking trace IDs per run row).

---

## 7. Knowledge bases & RAG

**Surface:** `KnowledgeBaseController`, `KnowledgeIngestionService`, `KnowledgeIngestionJobHandler`, `KnowledgeBaseDeletionJobHandler`, `WebsiteCrawlerService`, `FirecrawlSearchTool`, pgvector tables, `AdvancedRagAdvisor`, `DocumentReRanker`/`LlmDocumentReRanker`, `VectorStoreCacheAdvisor`.

**Test cases:**
1. Create KB → row persists with org scope.
2. Ingest by URL → `KnowledgeIngestionJobHandler` runs; chunks + embeddings in vector store; `IngestionStatusService` reflects progress.
3. Ingest by raw text → embeddings created; hash-dedup from `019-knowledge-content-hash-dedup.sql` prevents duplicates on re-ingest.
4. Ingest of an unreachable URL → job fails cleanly, status = FAILED, retry policy respected.
5. Delete KB → `KnowledgeBaseDeletionJobHandler` removes vector rows and JPA rows; related agents keep working (knowledge simply becomes empty).
6. RAG advisor: agent run with KB attached retrieves relevant chunks (seeded), irrelevant org's chunks are NOT retrieved (multi-tenant vector filter in `AgentSecurityFilters`).
7. Re-ranker enabled (`agent.rag.reranker.enabled=true`): top-N results reorder vs disabled run.
8. Vector store cache hit: repeated identical query within TTL resolves without a DB query (assert via query counter or log).
9. Crawler crawl-depth limit honored; domain allowlist respected; crawler politeness delay applied.
10. Ingestion under high concurrency does not deadlock pgvector writes.

---

## 8. Evaluations

**Surface:** `EvaluationController`, `EvaluationService`, `EvaluationRunJobHandler`, `scoring/` package, `SafetyService`.

**Test cases:**
1. Create eval suite → 201 + row.
2. Run suite (`POST /suites/{id}/run`) → `EvaluationRunJobHandler` executes; each case runs the target agent; results rows match case count.
3. Streaming eval (`GET /runs/{id}/stream`) → SSE frames updating status per case.
4. Scoring calculation: seed known-answer cases, verify expected pass/fail and aggregate score.
5. Eval run with agent that fails → per-case row records failure; suite still completes.
6. RBAC: only users with `EVAL_RUN` (or equivalent) role can trigger runs.
7. Rate-limiting on eval runs prevents resource exhaustion (`EvaluationService` is listed in rate-limit surface).

---

## 9. Teams & multi-agent orchestration

**Surface:** `TeamsController`, `TeamService`, `TeamOrchestrationEngine`, orchestrators (`Coordinator`, `Router`, `Planner`, `Sequential`, `Swarm`, `Debate`, `ActorCritic`, `Broadcast`), `TierEscalationValidator`, `TransitionValidator`.

**Test cases (one per orchestration strategy):**
1. **Coordinator**: leader delegates to workers; workers' responses are aggregated; transcript shows delegation chain.
2. **Router**: semantic classifier picks the correct specialist agent for a known-intent query.
3. **Planner**: plan is produced, steps executed in order, final answer is a synthesis.
4. **Sequential**: strict ordering; if step N fails, step N+1 never executes.
5. **Swarm**: parallel workers; `EphemeralSwarmContext` cleanup scheduler removes stale state within window.
6. **Debate**: N-round debate produces transcript with correct speaker rotation.
7. **ActorCritic**: actor output, critic feedback, revised output persisted as distinct messages.
8. **Broadcast**: query fans out to all; responses collected; timeouts for slow members honored.
9. **Tier escalation**: low-tier agent cannot delegate to higher-tier without validator approval (`TierEscalationValidator`).
10. **Transition validator** rejects illegal state transitions (e.g., direct worker→leader without coordinator consent).
11. Team run persists parent `AgentRun` + child runs per agent; team-level metrics aggregate correctly.
12. Team with one member disabled → orchestrator handles gracefully (skip, fail, or substitute, per spec).
13. Orchestration cap (`Multi-Agent Orchestration Limits` config) enforced: runaway loops terminated.

---

## 10. Workflows

**Surface:** `WorkflowsController`, `WorkflowService`, `WorkflowExecutionJobHandler`, `WorkflowResumeJobHandler`, `WorkflowStepExecutorExtension` SPI, workflow templates.

**Test cases:**
1. Create workflow from template → row persists, steps are valid.
2. Execute workflow (`POST /runs`) → `WorkflowExecutionJobHandler` executes steps in order; each step persists result.
3. Resume paused workflow (`POST /runs/{id}/resume`) → `WorkflowResumeJobHandler` picks up from last committed step.
4. Live viewer SSE (`/runs/{id}/stream` or equivalent) → frames match step transitions.
5. Step that throws → step row = FAILED, workflow = FAILED (or retried per policy); downstream steps not executed.
6. SPI extension step: register `WorkflowStepExecutorExtension` via `META-INF/services`; executes and integrates with result chain.
7. Concurrent workflows do not share step state.
8. Workflow with approval step pauses pending approval; approving resumes execution; rejecting aborts.
9. Workflow with scheduled trigger fires at the configured time (covered in §12).
10. Workflow audit trail: every step transition emits an `AuditLogService` row.

---

## 11. Approvals & HITL

**Surface:** `ApprovalService` (with `@Scheduled` SLA & cleanup), `HitlAdvisor`, `RequiresConfirmation`, `ApprovalsController`, `control/approval/`.

**Test cases:**
1. Tool annotated `@RequiresConfirmation` pauses the run; approval row created with status `PENDING`.
2. Approve via `POST /approvals/{id}/approve` → run resumes, tool executes, transcript has approval metadata.
3. Reject → run transitions to `CANCELLED` with rejection reason in payload.
4. Multi-approver policy: quorum of N must approve before resume.
5. SLA timeout: `ApprovalService.@Scheduled` at `approval-sla-check-ms` marks stale approvals; alert fires via `AlertingService`.
6. Cleanup scheduler (`approval-cleanup-ms`) removes approvals older than retention window.
7. Approver permissions: non-approver role cannot approve; 403.
8. Approval payload integrity: payload hash prevents tamper between create and approve.
9. List approvals respects org scope.
10. Concurrent approve + reject on the same row: exactly one wins (optimistic lock).

---

## 12. Schedules

**Surface:** `SchedulesController`, `ScheduleService`, `ScheduleExecutionPoller` (`@Scheduled` @ `schedule-poll-ms`).

**Test cases:**
1. Create schedule (cron) → row persists, next-fire time computed correctly.
2. Poller fires schedule whose `next_fire_at` is in the past → enqueues run via the queue service; `next_fire_at` advances.
3. One-shot schedule runs once and disables.
4. Schedule with invalid cron → 400 on create.
5. Disabled schedule is not fired even if due.
6. History endpoint (`GET /api/v1/schedules/{id}/history`) returns past fires with status.
7. Schedule firing while the DB is briefly unreachable → poller retries, does not double-fire.
8. Multi-instance safety: two app nodes running in parallel do not both fire the same schedule (row lock / advisory lock).
9. Schedule of a deleted agent → schedule disables itself (or fails fast with clear error) on next poll.

---

## 13. Memory

**Surface:** `MemoryController`, `MemoryService`, `MemoryOptimizationJobHandler`, `MemoryConsolidationWorker` (`@Scheduled` @ `memory-consolidation-ms`), `AgenticMemoryAdvisor`, `CulturalMemoryAdvisor`, `AgenticMemoryTools`, `ActiveMemoryTools`, `020-agentic-memory-vector-link.sql`.

**Test cases:**
1. Persist a memory via tool call → row in memory table with vector embedding.
2. Retrieve memory during next run → advisor injects matching memories into prompt.
3. Memory is org-scoped (seed cross-org memories, assert isolation).
4. `MemoryConsolidationWorker` runs → similar memories merged per policy; count decreases.
5. `MemoryOptimizationJobHandler` runs → low-salience memories archived; vector row count reflects change.
6. CRUD via `MemoryController`: list, get, edit, delete, with org scope + RBAC.
7. Cultural memory advisor injects org-level context; does not leak into other orgs.
8. Memory search (`POST /memory/search`) returns top-K with similarity scores; filters honored.
9. Deleting an agent does/does-not cascade to memory per spec — verify the intended behavior.
10. Import / export of memory bundles round-trips content and metadata.

---

## 14. Sessions

**Surface:** `SessionService`, `SessionRepository`, `SessionSummaryService`, `SessionSummarizationService`, `PersistentChatMemory`.

**Test cases:**
1. Create session implicitly via first run → row exists with agentId, userId, orgId.
2. GET session detail returns messages in chronological order.
3. Session token/context window exceeded → `SessionSummarizationService` produces summary; summary replaces oldest messages on next turn.
4. Delete session removes messages and summaries; vector memory references to this session are cleaned up.
5. List sessions is org-scoped and paginated.
6. Session ownership: user cannot read another user's session in the same org without admin role.
7. Session + run consistency: session message count = sum of user-turn inputs + run outputs across runs with this sessionId.

---

## 15. Registry

**Surface:** `RegistryController`, `DatabaseAgentRegistry` (loads on `ApplicationReadyEvent`).

**Test cases:**
1. Registry populates agents from DB at startup (seed rows pre-boot, assert registry contents after `@ApplicationReadyEvent`).
2. New agent created via API is immediately queryable through registry (cache invalidation).
3. Registry returns only org-visible + globally shared agents.
4. Registry lookup by non-existent id returns empty Optional, not NPE.
5. Registry refresh endpoint (if exposed) rebuilds cache from DB.

---

## 16. Extensions

**Surface:** `compute/extensions/`, `ExtensionHookAdvisor`, extension config controller, `WorkflowStepExecutorExtension` SPI.

**Test cases:**
1. Register extension config → hook advisor invokes it during the advisor chain at the documented point.
2. Extension that throws → run continues (or fails loudly, per policy) — verify the chosen semantics.
3. Disabled extension is bypassed without latency cost (measure chain traversal time with/without).
4. Extension can read + optionally mutate prompt context; mutations persist downstream.
5. SPI workflow-step extension: lifecycle test in §10.6.
6. RBAC: configuring extensions is admin-only.

---

## 17. MCP servers

**Surface:** `McpAdminController` (or equivalent), `McpConnectionPool` (`@EventListener(ApplicationReadyEvent)`), stdio MCP connections, `spring-ai-starter-mcp-client`.

**Test cases:**
1. Startup: `McpConnectionPool` attempts configured MCP connections from `spring.ai.mcp.client.stdio.connections.*` — current config has sqlite + github disabled by comment; assert pool initializes empty without error.
2. Add MCP server via admin API → pool registers connection; tools become available to agents.
3. MCP server that fails health check → excluded from routing; no 500 on agent run.
4. MCP tool invocation during a run appears in `ToolCallDTO` + audit log.
5. Removal via admin API closes the stdio process cleanly (no zombies).
6. MCP timeout on slow tool → circuit breaker trips; subsequent calls short-circuit.

---

## 18. Models

**Surface:** `ModelController`, `ModelService`, `ModelApiKeyMigrationService` (`@EventListener(ApplicationReadyEvent)`), `AgentModelResolverService`, `DynamicProviderInitializer`, `022-models-security.sql`, `012-agent-credentials.sql`.

**Test cases:**
1. List models → returns active providers only (ones enabled by `DynamicProviderInitializer`).
2. Create model config → key persisted encrypted (assert ciphertext in DB, plaintext after decryption in service).
3. Test model (`POST /test`) → round-trips a canned prompt via a stubbed ChatModel.
4. Patch disables model → agents using it fail fast with clear error on next run; do not crash app.
5. Delete model that has dependent agents → 409 with referential-integrity message; force-delete cascades intentionally.
6. `ModelApiKeyMigrationService` ran once at startup → flag set, subsequent starts no-op.
7. Provider disabled (no key) → model listing excludes that provider; attempt to create a model for it returns a clear error.
8. Model SSOT override via `global_settings` DB row overrides default resolution.
9. Multi-tenant: model rows scoped per org where applicable.

---

## 19. Security & safety

**Surface:** `ComplianceController`, `SafetyService`, `PIIAnonymizationAdvisor`, `StatefulStreamingPIIAdvisor`, `PromptInjectionAdvisor` + scanner, `ContentSafetyAdvisor`, `ModerationService`, `HallucinationDetectionAdvisor`, `PiiAdminController`, `compute/security/`.

**Test cases:**
1. PII in user prompt (SSN, email) → advisor anonymizes before LLM; original is redacted in logs/traces.
2. PII in streaming LLM output → `StatefulStreamingPIIAdvisor` redacts across chunk boundaries (SSN split across two chunks still fully redacted).
3. PII audit log row created per redaction event.
4. Prompt injection payload (e.g., "ignore previous instructions") → scanner flags; advisor blocks or sanitizes per policy; run finishes with `SECURITY_BLOCKED` status (or policy-defined).
5. Content safety: unsafe output → `ModerationService` blocks; user sees a policy-message; no raw unsafe content in transcript.
6. Hallucination detection: output unsupported by context → flagged in run metadata; metric increments.
7. PII admin CRUD (`/api/v1/pii-policies`): create/update/delete policies; changes take effect on next run (no restart required).
8. Compliance export (`/api/compliance/...`) returns structured report for the org (GDPR-ready).
9. Local regex moderation fallback (`LocalRegexModerationService`) when remote moderation API is unreachable.

---

## 20. Observability

**Surface:** `MonitoringController`, `OtlpSpanExportAdvisor`, `GenAiMetricsAdvisor`, `AgentLoggingAdvisor`, `FinOpsObservedEmbeddingModel`, `SloTrackingService` (`@Scheduled` @ `slo-evaluation-ms`), actuator endpoints, Micrometer Prometheus registry.

**Test cases:**
1. `/actuator/health` returns UP; subcomponents reflect DB + Redis + MCP statuses.
2. `/actuator/prometheus` exposes gen_ai_* metrics after a run.
3. OTLP advisor emits spans per LLM call; trace id propagates from inbound HTTP → LLM span → tool span.
4. MDC trace id survives virtual-thread hops in a streaming run.
5. `AgentLoggingAdvisor` produces a structured log row per run with expected fields.
6. Monitoring controller agent-metrics endpoint aggregates across runs correctly.
7. Security events endpoint reflects the security section's findings.
8. Sandbox health endpoint reports the Python sandbox container state.
9. `SloTrackingService` scheduler evaluates and records SLO breaches per `slo-evaluation-ms`.

---

## 21. FinOps

**Surface:** `control/finops/`, `FinOpsObservedEmbeddingModel`, `TelemetryService`, cost-per-run accounting, `FinanceTools`.

**Test cases:**
1. Run with stubbed model that emits token counts → cost row persisted with correct tokens × price.
2. Embedding calls via `FinOpsObservedEmbeddingModel` produce cost rows distinct from chat.
3. FinOps dashboard endpoint aggregates cost by agent, team, user, provider over a window.
4. Budget thresholds: exceeding a quota throttles further runs (429 or `BUDGET_EXCEEDED`) per policy.
5. Retention: cost rows older than `Data Retention Policies` are purged by `DataRetentionService`.
6. Cross-tenant isolation of cost data.

---

## 22. A2A mesh

**Surface:** `A2AController` (`/api/v1/a2a`), `control/a2a/`, `PeerHealthMonitor` (`@Scheduled` @ `peer-health-ms`), `006-a2a-encryption-patch.sql`.

**Test cases:**
1. Register peer with encrypted API key → ciphertext at rest; peer row visible in list.
2. Delete peer (`DELETE /peers/{alias}`) → row gone; outbound auth to that peer immediately fails.
3. `PeerHealthMonitor` marks unreachable peers unhealthy; metric updates.
4. Submit a2a task (`POST /tasks`, SSE) → streams progress; completes with result; row reflects end state.
5. Cancel a2a task (`DELETE /tasks/{id}`) → downstream peer is notified; local row = CANCELLED.
6. Encryption key rotation: `ModelApiKeyMigrationService`-style migration re-encrypts existing peer keys without downtime.
7. Cross-org peer isolation: org A cannot see or invoke org B peers.

---

## 23. User administration

**Surface:** `UserAdminController`, `UserAdminService`, `017-user-admin-fields.sql`, RBAC roles.

**Test cases:**
1. List users (admin role) → returns all users in same tenant scope.
2. Non-admin hitting `/admin/users` → 403.
3. Promote user role → effective on next login (token includes new roles).
4. Disable user → existing tokens still work until expiry (or are revoked per policy); login disabled.
5. Delete user → soft-delete or cascade per spec; owned resources re-assigned or preserved.
6. Bulk create users idempotent on re-submit (hash of input).
7. Admin-action audit trail present in `AuditLogService`.

---

## 24. Audit logs

**Surface:** `AuditLogService`, audit tables, `MonitoringController` or dedicated controller at `/admin/audit-logs`.

**Test cases:**
1. Every controller mutation (`POST`/`PATCH`/`DELETE` on business endpoints) writes exactly one audit row with user, org, action, resource, timestamp.
2. Authentication events (login success/fail, logout) produce audit rows.
3. Audit log endpoint: filter by user, date range, resource type; pagination works.
4. Audit rows are immutable: UPDATE attempts via JDBC return 0 rows affected (or are blocked by trigger).
5. Retention: rows older than retention window removed by `DataRetentionService`.
6. Org-scoped visibility — audit log of org A never shows to admin of org B.

---

## 25. Alerts & integrations

**Surface:** `AlertingController`, `AlertingService` (`@Scheduled`), `AlertIntegrationService` (`@EventListener`), `018-alert-integrations.sql`.

**Test cases:**
1. Create alert rule → row persists; rule evaluates on next scheduler tick.
2. Rule condition met → alert event row created; `AlertIntegrationService` dispatches to configured integrations (Slack/webhook/email per config).
3. Integration with invalid webhook URL → dispatch fails, retry policy applies, final-failure is recorded.
4. Acknowledge event → status transitions; same event not redelivered.
5. Delete rule → no more events; historical events remain.
6. Event listener path: `AlertIntegrationService.@EventListener` correctly consumes `AlertingService` emissions (end-to-end through Spring event bus, not polling).
7. Alert cooldown prevents flapping (if configured).

---

## 26. Settings

**Surface:** `ConfigController`, `SettingsService`, `global_settings` DB table.

**Test cases:**
1. Read default settings → returns values from `application.properties` where no DB override exists.
2. Write org-level setting → overrides default for that org; other orgs unaffected.
3. Write global setting (admin-only) → overrides default globally.
4. Default-model-resolution override via `global_settings` is respected by `AgentModelResolverService` on next run.
5. Invalid setting key → 400.
6. RBAC: non-admin cannot write org-wide or global settings.
7. Cache: settings are cached; POST invalidates and next GET is fresh.

---

## 27. Cross-cutting runtime concerns

### 27.1 Multi-tenancy (org scoping)
- `TenantContextFilter` reads `X-Org-Id` header or resolves from JWT; every business query applies Hibernate filter `tenantFilter`.
- **Tests:** For each business entity (agents, runs, sessions, memory, KB, workflows, schedules, approvals, peers, models, settings, alerts), seed rows under orgA and orgB, assert orgA user sees only orgA. Missing orgId → 400 (or configured default). `AgentSecurityFilters` denies vector queries when `AgentContextHolder.orgId` is null.

### 27.2 Rate limiting
- `RateLimitingFilter` enforces per-user-per-second caps (see `API Rate Limiting` in `application.properties`).
- **Tests:** Rapid-fire N+1 requests; N succeed, rest return 429. Limits reset per-second. Bypass headers (if any) honored only for whitelisted service accounts.

### 27.3 Background job queue
- `PersistentJobQueueService` (`@Scheduled` @ `batch-poll-ms`) pulls jobs from `background_jobs` table via `JobHandlerRegistry`.
- **Tests:** Enqueue job of each handler type; poller picks up within one poll interval; handler runs; terminal status persisted. Failing handler retries per policy, then DEAD_LETTER. Two app nodes don't both claim the same row (row lock). Queue metrics reflect depth.

### 27.4 Scheduled tasks
Each `@Scheduled` in the codebase:
- `MemoryConsolidationWorker` @ 5s (fast for tests).
- `BatchReasoningQueueService` @ 30s.
- `PersistentJobQueueService` @ 30s.
- `ScheduleExecutionPoller` @ 60s.
- `AlertingService` @ 60s.
- `PeerHealthMonitor` @ 120s.
- `SloTrackingService` @ 300s.
- `EphemeralSwarmContext` cleanup @ 900s.
- `ApprovalService` SLA @ 900s, cleanup @ 3600s.
- `DataRetentionService` daily @ 03:00.

**Tests:** For each, verify behavior on-trigger (rather than waiting the real interval — override the `agentmanager.scheduler.*-ms` property to a short value in the specific test and use `Awaitility`, or invoke the method directly via `@Autowired` bean after seeding).

### 27.5 Virtual threads & MDC propagation
- Micrometer Context Propagation is wired in.
- **Tests:** 50 concurrent streaming runs under different principals — no cross-contamination of `userId`/`orgId`/`traceId` in run rows or logs. MDC survives `Flux.subscribeOn(boundedElastic)` hop in `AgentStreamManager`.

### 27.6 Circuit breaker / retry
- `CircuitBreakerAdvisor` uses Resilience4j.
- **Tests:** 5 consecutive fake-model failures trip breaker; next call short-circuits to fallback; after `waitDurationInOpenState` breaker half-opens; single successful call closes it. `@EnableRetry` + `StructuredOutputRetryAdvisor` retries up to `agent.guardrails.structured-output.max-retries`, then surfaces final error.

### 27.7 Data retention & erasure
- `DataRetentionService` daily cron purges per policy.
- `ErasureOrchestrationService` + `ErasureRequestJobHandler` + `erasure/KnowledgeErasureHandler`.
- **Tests:** Seed rows older than each retention window; run cron; assert deletion. Submit GDPR erasure request for a user; job handler wipes memory, sessions, runs, knowledge owned by that user; audit row records the erasure. Erasure is idempotent.

### 27.8 Python sandbox
- `PythonSandboxService`, `PythonCodeInterpreterTool`, Testcontainers for Docker isolation.
- **Tests:** Tool call runs Python; stdout captured; tool output persisted. Infinite loop killed at wall-clock limit. Network access denied. Filesystem writes confined to ephemeral volume. Container leak: no containers left behind after test class.

### 27.9 Startup & bootstrap
- `DynamicProviderInitializer` pre-bean.
- `ApplicationReadyEvent` listeners: `McpConnectionPool`, `RunExecutionManager`, `DatabaseAgentRegistry`, `ModelApiKeyMigrationService`.
- **Tests:** Boot with no API keys → providers disabled, startup banner shows DISABLED, context comes up healthy. Boot with keys → providers enabled, registry loaded, MCP pool initialized, key migration ran. Boot with a corrupt/in-flight run in DB → `RunExecutionManager` readiness hook marks it FAILED or recovers it.

### 27.10 Cache behavior (`@EnableCaching`)
- **Tests:** Cached GETs return consistent result; invalidations on POST/PATCH/DELETE are tight (no stale reads after mutation); cache isolation per org where applicable.

### 27.11 Error taxonomy
- Global `ResponseStatusException` / `@ControllerAdvice` behavior, custom exceptions in `core/exception/`.
- **Tests:** `ResourceNotFoundException` → 404; `BusinessValidationException` → 400; auth failures → 401; RBAC denials → 403; every error body matches a documented shape (code, message, traceId). No stack traces leak to clients.

---

## 28. Test file organization

```
src/test/java/com/operativus/agentmanager/integration/
├── BaseIntegrationTest.java                (harness)
├── auth/                                   (§3)
├── dashboard/                              (§4)
├── agents/                                 (§5)
├── chat/                                   (§6)
├── knowledge/                              (§7)
├── evaluations/                            (§8)
├── teams/                                  (§9)
├── workflows/                              (§10)
├── approvals/                              (§11)
├── schedules/                              (§12)
├── memory/                                 (§13)
├── sessions/                               (§14)
├── registry/                               (§15)
├── extensions/                             (§16)
├── mcp/                                    (§17)
├── models/                                 (§18)
├── security/                               (§19)
├── observability/                          (§20)
├── finops/                                 (§21)
├── a2a/                                    (§22)
├── admin/
│   ├── users/                              (§23)
│   ├── audit/                              (§24)
│   └── alerts/                             (§25)
├── settings/                               (§26)
└── crosscutting/
    ├── TenancyIsolationTest.java           (§27.1)
    ├── RateLimitTest.java                  (§27.2)
    ├── JobQueueTest.java                   (§27.3)
    ├── SchedulerTest.java                  (§27.4)
    ├── VirtualThreadMdcTest.java           (§27.5)
    ├── ResilienceTest.java                 (§27.6)
    ├── RetentionErasureTest.java           (§27.7)
    ├── SandboxIsolationTest.java           (§27.8)
    ├── StartupLifecycleTest.java           (§27.9)
    ├── CachingTest.java                    (§27.10)
    └── ErrorTaxonomyTest.java              (§27.11)
```

Each directory holds one test class per controller / per orchestrator / per handler, named `<Feature>RuntimeTest.java`. Shared test data builders live in `src/test/java/com/operativus/agentmanager/integration/support/TestData.java`.

---

## 29. What this document is NOT

- Not a unit test plan. Unit tests for classifiers, parsers, validators, and pure functions live alongside the source and use plain JUnit + Mockito. They are not the subject here.
- Not a load/performance plan. Throughput, p99 latency, and soak testing are separate and belong in a Gatling/k6 harness.
- Not a UI test plan. The React app is covered by its own Playwright/Cypress suite.
- Not exhaustive of every field-level validation. Field validation is covered by focused controller-slice tests where appropriate; this matrix is for *behavioral* runtime coverage.
