# Walkthroughs — Demo & Bug-Hunt Guide

Practical guide to the demo environment + walkthrough scripts that surface and
pin regressions. This is the workflow that produced the May 2026 bug-fix arc:
seed the demo data, run scripted probes against a live backend, fix what
fails, and lock the fix down with a numbered walkthrough script.

The same path is repeatable any time a release candidate needs a smoke pass
or a new bug needs a reproducer.

---

## Layers at a glance

| Layer | Path | What it does | LLM needed |
|---|---|---|---|
| **A. Demo seed** | `scripts/seed-demo.sh` | Boots BE under `SPRING_PROFILES_ACTIVE=demo` so Liquibase applies every `context:"demo"` changeset (users, agents, KBs, teams, sessions, runs, A2A, evaluations, audits, jobs, schedules, HITL pendings, vectors). | No |
| **B. Walkthrough scripts** | `scripts/walkthroughs/NN-*.sh` | Pure-REST regression probes. Each script exits non-zero if any assertion fails. Suitable for a future nightly CI smoke tier. | No |
| **C. Live-LLM end-to-end** | `scripts/demo-tasks-team-walkthrough.sh` | Real-provider smoke against Anthropic Haiku (TasksMode team). First contact with a live model. | **Yes** |

Layer A is the foundation — the other two assume the demo data is present.

---

## Prerequisites (one-time)

1. **Docker running** — Postgres + pgvector come up via `docker-compose`.
2. **Java 25**, `./mvnw` on `PATH`.
3. **Anthropic API key** (only for Layer C). Either:
   - `ANTHROPIC_API_KEY=sk-ant-…` in `.env` at repo root, OR
   - `POST /api/v1/provider-credentials` with `provider=ANTHROPIC` after boot.
4. **Demo login** — `sesker` / `yamaha69` (ADMIN in `DEFAULT_SYSTEM_ORG`).
   Demo seed adds four more users (all `yamaha69`):

   | User | Roles | Org |
   |---|---|---|
   | `demo-admin` | ADMIN, USER | DEMO_ACME |
   | `demo-analyst` | USER | DEMO_ACME |
   | `demo-viewer` | VIEWER | DEMO_ACME |
   | `demo-ops` | USER | DEMO_GLOBEX |

---

## Layer A — Boot the demo environment

### Fresh boot

```bash
./scripts/seed-demo.sh
```

This brings up Postgres, then runs `SPRING_PROFILES_ACTIVE=demo ./mvnw
spring-boot:run`. The `demo` profile pins
`spring.liquibase.contexts=demo` so every `demo-*.sql` changeset under
`agent-manager/src/main/resources/db/changelog/changes/demo/` is applied
on startup. Watch for the `DemoStartupBanner` WARN line in the log — that's
the signal the demo profile is active.

### Liquibase-only (no BE start)

```bash
./scripts/seed-demo.sh --liquibase
```

Useful when the BE is being driven another way (IDE, `start-dev.sh`, etc.)
but the demo rows still need to be applied.

### Wipe / reseed

```bash
./scripts/reset-demo.sh            # wipe demo rows; schema intact
./scripts/reset-demo.sh --reseed   # wipe then immediately reapply demo
```

`reset-demo.sh` triggers `demo-099-wipe.sql` via the `demo-wipe` Liquibase
context. Production seed and schema are untouched.

---

## Layer B — Walkthrough scripts (`scripts/walkthroughs/`)

Each script is a deterministic, no-LLM REST probe that verifies a specific
fix or contract. Numbered to match the historical demo arc, so `14-` pins
"Demo #14" findings.

### Convention

Every walkthrough follows the same shape (reference:
`22-workflow-condition-loop-steps.sh`):

- `set -euo pipefail`
- `require(label, expected, actual)` helper — exits non-zero on mismatch,
  aligned PASS/FAIL output
- `cleanup()` trap on EXIT — destructive walkthroughs tear down their own
  test artifacts even on failure
- `--keep` flag to suppress cleanup for debugging
- Envvar overrides: `AGM_BASE_URL`, `AGM_USER`, `AGM_PASSWORD`, plus
  per-script knobs (e.g. `AGENT_ID`, `FOREIGN_USER_ID`)
- PR description names the line(s) you'd revert to fail each assertion
  (regression provenance)

### Run a single walkthrough

```bash
./scripts/walkthroughs/14-agent-create-duplicate-id-409.sh
```

Override defaults as needed:

```bash
AGM_BASE_URL=http://localhost:8080 \
AGM_USER=demo-admin \
AGM_PASSWORD=yamaha69 \
./scripts/walkthroughs/27-compliance-cross-org-probe.sh
```

Exit code `0` = every assertion passed. Anything else = read the FAIL line.

### Run them all

```bash
for w in scripts/walkthroughs/*.sh; do
  echo "=== $w ==="
  "$w" || { echo "FAILED: $w"; exit 1; }
done
```

### Current catalog

| Script | Pins | Verifies |
|---|---|---|
| `14-agent-create-duplicate-id-409.sh` | PRs #923 + #924 + #926 | Duplicate POST → 409 with `hint`; fresh POST → 201; `GET /{id}` → 200 |
| `22-workflow-condition-loop-steps.sh` | PR #918 | CONDITION step (`contains:dollar`) → 201; LOOP step (`max:5\|until:done`) → 201; AGENT step with bogus id → 404 (regression guard) |
| `27-compliance-cross-org-probe.sh` | PRs #931 + #932 + #933 | All 4 compliance endpoints return 404 for a foreign-org `userId` |

### When a script fails

The FAIL line shows `label`, expected status, and actual status. From there:

1. `tail -200 /private/tmp/agm-backend.log` for the request the script just made.
2. If the failure is in a CRUD path, re-run with `--keep` (when supported)
   to inspect the lingering DB rows.
3. If a status code is "almost right" (e.g. 500 where 404 was expected),
   check `GlobalExceptionHandler` — the wrong exception type is reaching
   the catch-all.

---

## Layer C — Live-LLM end-to-end

`scripts/demo-tasks-team-walkthrough.sh` runs a TasksMode team against
Anthropic Haiku and asserts on the run-event timeline, task table rows,
and final synthesized response. See
[`scripts/demo-tasks-team-walkthrough.md`](../demo-tasks-team-walkthrough.md)
for the full per-stage breakdown.

### Run it

```bash
export AGM_BEARER="$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"sesker","password":"yamaha69"}' | jq -r .token)"

./scripts/demo-tasks-team-walkthrough.sh
```

Optional overrides:

```bash
COORDINATOR_MODEL=claude-haiku-4-5 \
WORKER_MODEL=claude-haiku-4-5 \
ORG_ID=DEFAULT_SYSTEM_ORG \
./scripts/demo-tasks-team-walkthrough.sh --cleanup
```

> Only `claude-4-5-haiku` reliably works for tool-calling agents in this
> codebase right now — Sonnet/Opus return "credit balance too low" on the
> dev key. Override `COORDINATOR_MODEL` accordingly.

### Cache-busting the LLM path

`VectorStoreCacheAdvisor` will silently return cached responses for
semantically similar prompts. Signature of a cache hit in `agent_runs`:
`input_tokens=null`, `output_tokens=null`, `duration_ms≈200`,
`toolCallCount=0`.

To force a real model call, prefix prompts with a NONCE:

```bash
NONCE="[probe-$(uuidgen | head -c8)]"
curl -X POST .../runs -d "{\"input\":\"$NONCE What's the price of AAPL?\"}"
```

Structurally distinct phrasing busts the cache more reliably than the NONCE
alone — both together is best for high-confidence live verification.

---

## How bugs got found in the May 2026 arc

The arc closed ~19 defects across PRs #923–#946. They surfaced from the
layers above in this order:

1. **Demo seed boot** caught schema / Liquibase mismatches (#14a tier enum,
   #50 missing org_id column, #51 agent_audits org_id NULL).
2. **Manual UI/REST exploration** on the seeded data surfaced the
   user-facing bugs: duplicate-ID 409 (#10), missing `GET /agents/{id}`
   (#9), 413 on big uploads (#15), compliance cross-tenant exposure (#47).
3. **Layer C live LLM** surfaced the multi-turn memory bug (#2 turn 2) and
   the followups meta-prompt (#7). These don't show up against
   `FakeChatModel` — only first contact with a real provider exposes the
   advisor-chain wiring gap.
4. **Targeted spy advisors** — temporary `SpyAdvisor` between
   `ConversationIdInjectionAdvisor` (order `MIN_VALUE+100`) and
   `MessageChatMemoryAdvisor` (`MIN_VALUE+1000`), then `PostMCMSpyAdvisor`
   at `MIN_VALUE+1500` — gave the definitive diagnostic that pinpointed
   the response-context propagation gap behind #2. Remove these before
   commit.

After each fix, a walkthrough script under `scripts/walkthroughs/` locks
the regression down. Pattern:

> Fix → manual REST repro on the demo → write the walkthrough → ship it as
> a separate PR so the regression guard is reviewable on its own.

---

## Authoring a new walkthrough

Copy `22-workflow-condition-loop-steps.sh` as the template. Required:

1. Number it with the demo-arc number it pins.
2. Top-of-file comment naming the PRs it verifies + the exact lines you'd
   revert to fail each assertion.
3. `set -euo pipefail`, `require()` helper, `cleanup()` trap, `--keep`
   flag, envvar overrides.
4. PR description should mirror the comment header — anyone retargeting
   the script later needs the regression provenance.

---

## Quick reference

```bash
# Boot demo BE
./scripts/seed-demo.sh

# Reset demo data
./scripts/reset-demo.sh --reseed

# Run one walkthrough
./scripts/walkthroughs/14-agent-create-duplicate-id-409.sh

# Run all walkthroughs
for w in scripts/walkthroughs/*.sh; do "$w" || exit 1; done

# Live-LLM smoke (needs Anthropic key)
./scripts/demo-tasks-team-walkthrough.sh

# Tail BE log
tail -f /private/tmp/agm-backend.log
```

## Related docs

- [`scripts/demo-tasks-team-walkthrough.md`](../demo-tasks-team-walkthrough.md)
  — per-stage breakdown of the live-LLM team walkthrough
- `agent-manager/docs/how-to/execute-runtime-tests.md` — Java
  runtime/integration test tier (`./mvnw test -Dexcluded.groups=`)
