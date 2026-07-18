# Composio WireMock Fixture Validation (A21)

Per `docs/plans/agm-agentos-tool-parity-impl.md` §6 Phase 6 (T014) and the platform
roadmap's A21 anti-pattern: WireMock-based runtime tests are independent ground truth
ONLY when the mock response shapes match what Composio actually returns. Inline
fixtures embedded in `ComposioAdapterRuntimeTest.java` were authored from spec
assumptions about Composio's `{successful, data, error}` envelope and `Retry-After`
behavior; this document tracks their validation status against captured live responses.

## Status

`<!-- VALIDATED: NOT YET — pending Composio API key provisioning -->`

The dev environment does not have a `COMPOSIO_API_KEY` set against
`https://backend.composio.dev`, so the validation step is **deferred** as documented in
the parent session memory (`.claude/session-memory/15-issues.md` — A21 fixture validation
deferred). When a key is available, follow the procedure below and replace the status
header above with `VALIDATED: YYYY-MM-DD`.

## Validation Procedure (when API key is available)

1. Set `COMPOSIO_API_KEY` and a test connection ID for one of the default
   `enabled-actions` (for example, `GMAIL_SEND_EMAIL`).
2. Issue a real call against `backend.composio.dev`:
   ```bash
   curl -X POST "https://backend.composio.dev/api/v2/actions/GMAIL_SEND_EMAIL/execute" \
     -H "X-API-Key: $COMPOSIO_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{"connectionId":"<your-conn>","input":{"to":"you@example.com","subject":"test","body":"hi"}}'
   ```
3. Capture the response body. Confirm the envelope matches the WireMock fixtures used
   in `ComposioAdapterRuntimeTest`:
   - 200 success → `{"successful": <bool>, "data": {...}, "error": null}` (or similar).
   - 4xx / 5xx → JSON error body (any shape — the adapter only inspects the HTTP status code).
   - 429 → `Retry-After` header present (Composio may use seconds OR an HTTP date; the
     adapter parses seconds and skips on `NumberFormatException`).
4. If divergent, update the inline `withBody(...)` fixtures in
   `ComposioAdapterRuntimeTest.java` to match the captured shape and re-run the test.
5. Update the status line at the top of this document with the validation date.

## Why deferred and not blocked

The runtime test's value (error-path coverage, HITL tier wiring, circuit breaker
correctness, naming-collision detection, registry cap behavior) does NOT depend on
exact response envelope shape — the adapter passes the response body through unchanged
in the `response` field of its output JSON, and assertions read adapter-injected
metadata (`provider`, `action`, `durationMs`, `error`). Validation is a hardening step,
not a gate.

## A21 anti-pattern reminder

If a future session adds new test cases that DO depend on response envelope shape
(e.g., extracting a specific field from `data`), that case MUST be covered by validated
fixtures or marked `@Disabled` until validation completes. Re-read `docs/plans/agm-platform-completion-roadmap.md` §3 anti-pattern A21 for the rationale.
