# Agent Manager — Claude Instructions

## Code Exploration (use in order)
1. `search_graph` / `get_code_snippet` / `trace_path` via codebase-memory-mcp for any code lookup
2. Fall back to `Read` / `grep` only for config files, non-code files, or files being prepped for `Edit`
3. Never read a full file when a symbol-level lookup suffices

## Context Management
- `ctx_batch_execute` for any command producing >20 lines of output
- `ctx_search` for follow-up queries against already-indexed output
- `ctx_execute_file` for analysis/data-processing only — never for file creation
- `Write` / `Edit` for all file creation and modification
- `Read` only when preparing a file for `Edit`

## Package Layout
- `compute/` — agent execution: `advisor/`, `mcp/`, `provider/`, `service/`, `teams/`, `tools/`
- `control/` — REST + persistence: `a2a/`, `controller/`, `dto/`, `repository/`, `service/`, `websocket/`
- `core/` — domain primitives: `entity/`, `event/`, `exception/`, `model/`, `registry/`, `spi/`
- DTOs live in `control/dto/` — NOT `core/`

## Code Style
- No comments unless the WHY is non-obvious
- No backwards-compat shims — app is pre-launch, refactor directly
- Always `./mvnw clean compile` before committing (incremental cache masks overload-ambiguity errors)

## Tests
- Default: `./mvnw test` skips `@Tag("integration")` via Surefire `excludedGroups`
- To run integration tests: `./mvnw test -Dexcluded.groups= -Dtest=ClassName`
- `-Dgroups=integration` alone does NOT work — must pair with `-Dexcluded.groups=`
- Integration tests extend `BaseIntegrationTest` (Testcontainers Postgres + full Spring context)

## PR Workflow
- One branch/PR per plan item, stacked on predecessor
- Retarget all stacked PR bases to `main` BEFORE merging predecessor
- `./mvnw clean compile` before every commit

## Scope
- `website/` is out of scope — exclude from all audits and PRs
- Project hydration doc: `docs/mem0-hydration.md`
