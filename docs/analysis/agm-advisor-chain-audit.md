# Advisor Chain — Pre-launch Trim Audit

_Audited: 2026-05-27_

## Background

The 2026-05-10 architecture review verdict flagged the advisor chain in `compute/advisor/` as **overscoped for current stage**. This doc is the follow-up scoping work for that finding: inventory every advisor, categorize by wiring + value, and recommend KEEP / DROP / DEFER.

**Purpose:** decide what to actually remove before launch. No code is touched in this PR — this is a planning artifact for the user to react to.

**Counts:** 25 `.java` files under `compute/advisor/`. 19 are advisors; 5 are support classes (moderation, scanner, annotation). `GenAiMetricsAdvisor` lives in `compute/monitoring/` for telemetry-domain reasons.

## Recommendation summary

| Bucket | Count | What it means |
|---|---|---|
| **KEEP** (always-on, ship-blocking value) | 7 | Required for any LLM product worth shipping |
| **KEEP** (conditional, lightweight) | 5 | Only fires when feature is configured; near-zero idle cost |
| **DEFER** (keep code, candidate for removal post-launch) | 5 | Real cost in complexity / latency; investigate usage before MVP |
| **INVESTIGATE** (probably KEEP, need data) | 2 | One-question-answer-then-decide items |
| Support classes (not advisors) | 5 | Helpers; no trim decision needed |
| **TOTAL** | 24+1 (GenAiMetrics) | |

## Full inventory

### KEEP — required for any external launch (7)

| Advisor | Wiring | Why required |
|---|---|---|
| `AgentLoggingAdvisor` | always-on | Observability baseline. Without it, ops can't reconstruct what an agent run did. |
| `PIIAnonymizationAdvisor` | always-on at order 10 | Compliance baseline. Pinned by `AdvisorPiiBoundaryContractTest`. Removing it is a privacy regression. |
| `PromptInjectionAdvisor` | always-on | Security baseline against prompt-injection via user input. |
| `ContentSafetyAdvisor` | always-on (and a hard-gate on streaming) | Safety baseline against unsafe model output. |
| `HitlAdvisor` | always-on | Approval gate for destructive tool calls. Pinned by `core/spi/ToolTierResolverProvider`. |
| `AgentIdInjectionAdvisor` | always-on per session | Injects `agentId` + `sessionId` into the run context for downstream consumers (logs, metrics, audit). Tiny class but load-bearing. |
| `GenAiMetricsAdvisor` (in `compute/monitoring/`) | always-on | Token + cost tracking. Powers FinOps + budget gating. Removing it breaks the cost story. |

### KEEP — conditional, lightweight (5)

Each fires only when its specific feature is configured on a per-agent basis. Zero cost when off.

| Advisor | Triggers when | Trim cost |
|---|---|---|
| `StructuredOutputRetryAdvisor` | agent has `enforceJsonOutput=true` | Removing breaks every agent using JSON tool-output mode. |
| `AgenticMemoryAdvisor` | agent has `memoryEnabled=true` | Removing breaks the cross-session memory feature. |
| `ConversationIdInjectionAdvisor` | persistent chat memory enabled | Pair member with the response advisor below; needed for `addHistoryToMessages` semantics. |
| `ConversationIdResponseAdvisor` | same as above | Response-side pair of the injection advisor. They ship together. |
| `AdvancedRagAdvisor` | agent has non-empty `knowledgeBaseIds` | Removing breaks RAG. KB-bound agents would error; pinned by `KnowledgeBindingGoldenPathSmokeRuntimeTest`. |

### DEFER — candidates for removal post-launch (5)

These add real complexity / latency. Honest question for each: **does the value justify the cost at MVP?** Default answer is "no, defer / remove" unless evidence says otherwise.

| Advisor | Concern | What to investigate before removing |
|---|---|---|
| `HallucinationDetectionAdvisor` | Adds an extra LLM call per response to score factuality. Real latency + cost hit. | Is the score ever consulted by downstream code? Has anyone seen a real catch? If both "no", drop. |
| `CulturalMemoryAdvisor` | Only fires when `cultureManager` bean is wired. Is the feature shipped in any tenant? | Grep for `CultureManager` callers; if none, the advisor is dead code. |
| `StatefulStreamingPIIAdvisor` | Duplicates static `PIIAnonymizationAdvisor` specifically for streaming `TIER_2_STRICT` agents. Adds sliding-window state per stream. | Are any tenants on `TIER_2_STRICT` + streaming? If not, the static advisor + a documented "no streaming for tier-2" policy is sufficient. |
| `ExtensionHookAdvisor` | Generic pre/post hook plugin. Powerful but untyped. | Is ANY agent declaring `preHooks` or `postHooks` today? If not, the surface is unused complexity. |
| `LlmDocumentReRanker` (paired with `DocumentReRanker`) | Adds an extra LLM call per RAG retrieval. Gated by `agent.rag.reranker.enabled=false` (off by default). | Has anyone turned it on? If no production tenant uses it, ship `DocumentReRanker` as a no-op or drop the LLM variant. |

### INVESTIGATE — probably KEEP, want one data point (2)

| Advisor | The data point that decides it |
|---|---|
| `CircuitBreakerAdvisor` | How often has it tripped on a real run? If zero in N months, the value at MVP scale is low — but the cost is also low (Resilience4j is lightweight). Likely KEEP. |
| `VectorStoreCacheAdvisor` | How much RAG traffic does the median tenant generate? If RAG is rare at MVP, the cache is dead weight. If RAG is hot, it's a meaningful cost saver. |

Also `OtlpSpanExportAdvisor` belongs here: only valuable if there's an OTLP collector consuming the spans. If there isn't one yet, the advisor is consuming JVM cycles to emit spans that go nowhere. Verdict depends on whether ops has wired a collector.

### Support classes — no trim decision (5)

These aren't advisors. They live in `compute/advisor/` only because they support the advisors that are there.

| File | Role |
|---|---|
| `LocalRegexModerationService` | Local fallback for `ModerationService` when OpenAI moderation isn't wired. |
| `ModerationService` | Interface implemented by both `LocalRegexModerationService` and OpenAI-backed impl. |
| `ModerationResult` | DTO returned by `ModerationService`. |
| `PromptInjectionScanner` | Static scanner used inside `PromptInjectionAdvisor`. |
| `RequiresConfirmation` | Annotation read by `HitlAdvisor` to mark destructive tools. |

## Recommended trim plan (if you accept this audit)

Cheapest-to-most-expensive removal path:

1. **One PR per DEFER candidate** (5 PRs total). Each PR:
   - Greps the codebase for actual usage of the feature the advisor gates
   - If usage found: closes the PR, marks the advisor KEEP in this doc
   - If no usage: removes the advisor + its wiring in `AgentClientFactory` + any tests that reference it
2. **The two INVESTIGATE items get one PR each** — fetch the operational data, then decide.

If the audit is correct, **~5–7 advisors could go**. The chain shrinks from 18 to ~11. That's a real cognitive-load improvement and a small but real perf gain (each advisor adds a stack frame on every LLM call).

If the user wants to defer the trim entirely (focus on shipping the launch checklist first), that's also fine — the cost of carrying these advisors at MVP is real but small. The audit is durable: come back to it post-launch when you have usage data to decide concretely.

## Open questions for the user (the actual scoping convo)

1. **Is `cultureManager` wired and used by any tenant?** — decides `CulturalMemoryAdvisor`.
2. **Are any tenants on `TIER_2_STRICT` AND using streaming?** — decides `StatefulStreamingPIIAdvisor`.
3. **Does any agent declare `preHooks` or `postHooks` today?** — decides `ExtensionHookAdvisor`.
4. **Has `agent.rag.reranker.enabled` ever been flipped to `true` for any tenant?** — decides `LlmDocumentReRanker`.
5. **Does `HallucinationDetectionAdvisor`'s score get read by anything?** — decides that advisor.
6. **Is there an OTLP collector ingesting spans?** — decides `OtlpSpanExportAdvisor`.

Answer these 6 and the trim becomes mechanical.

## Code-grep recon (2026-05-27)

Pre-launch state means "tenant usage" answers are all "no tenants" — but the codebase still tells us which features are even wired vs. dead. Recon below gives evidence-grounded answers without waiting for operator confirmation.

| Q | Finding | Verdict |
|---|---|---|
| **1 — `cultureManager`** | `@Service`-annotated, **always wired**. `CulturalMemoryAdvisor` is added to **every** agent chain in `AgentClientFactory` (no conditional gate). Adds a frame on every call regardless of whether the manager has data. | **Needs operator confirmation.** If no tenant has cultural-memory rules, drop. If any tenant uses it, keep — but consider gating the advisor on non-empty rules so it can no-op cheaply. |
| **2 — `TIER_2_STRICT` + streaming** | Default tier is `TIER_1_STANDARD`. `StatefulStreamingPIIAdvisor` **bypasses on tier-1** (cheap early return: one field check per stream chunk). Only the demo profile has 2 `TIER_2_STRICT` agents. | **KEEP** (upgraded from DEFER). Conditional is already cheap and the protection is meaningful when activated. |
| **3 — `preHooks` / `postHooks`** | Schema columns default to `'[]'`. `DatabaseAgentRegistry` passes `null, null` for both fields when loading demo agents. `AgentClientFactory` only wires `ExtensionHookAdvisor` **when** `def.preHooks()` OR `def.postHooks()` is non-empty. No non-demo seed uses hooks. **At MVP the advisor is wired for zero agents.** | **DROP** — `ExtensionHookAdvisor` + its conditional wiring. Keep the entity columns for future use. |
| **4 — `agent.rag.reranker.enabled`** | Default `false`. No env-var override in `application.properties`. Not flipped anywhere. | **DROP `LlmDocumentReRanker`** (and the conditional that picks between local + LLM reranker; keep the simple local one as the default). |
| **5 — `HallucinationDetectionAdvisor` score consumer** | `grep -rn "hallucinationScore\|HallucinationResult"` against `src/main/java` returns **zero hits**. The advisor produces a metric/log but **no downstream code reads the score**. | **DROP.** Advisor adds an extra LLM call per response producing data nothing consumes. |
| **6 — OTLP collector** | `agentmanager.otlp.enabled=false` default; no override. `OtlpSpanExportAdvisor` is gated off at MVP. | **KEEP as no-op** (upgraded from DEFER). The gate means zero cost when off; removing means re-implementing when first ops team wants distributed tracing. |

## Recommended trim (post-recon)

Three advisors with **strong evidence-based DROP** verdicts:

1. `ExtensionHookAdvisor` — zero agents use hooks; wiring is dead.
2. `HallucinationDetectionAdvisor` — score consumed by nothing.
3. `LlmDocumentReRanker` — feature off by default, never enabled.

One that needs **one operator confirmation** before deciding:

4. `CulturalMemoryAdvisor` — always-wired. Question: "Do you have any tenant using cultural memory rules?" If no, drop. If yes, gate it on non-empty rules.

Upgraded to KEEP after recon: `StatefulStreamingPIIAdvisor` (cheap conditional), `OtlpSpanExportAdvisor` (gated off cheaply).

**Net trim:** 3 immediate-DROP PRs + 1 conditional-DROP (pending the cultural-memory question). Chain shrinks from ~18 to ~14–15 advisors. Smaller win than the original audit's worst case, but evidence-grounded and low-risk.
