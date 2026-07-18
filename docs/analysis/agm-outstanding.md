# Agent Manager — Outstanding Work to Reach Launch

_Last updated: 2026-05-25_

## Summary

The product itself (backend + UI + tests) is in a state most pre-launch apps are not. The deployment, operations, and business layers are mostly absent. This doc inventories what's already in place vs. what is genuinely blocking a v1 launch — to keep future work targeted at shipping, not at indefinite hardening.

## What's already in place

- **Backend correctness.** Spring Boot 4 app + ~1,675 unit tests + ~320 integration tests + 3 golden-path smoke tests + active CI (re-enabled 2026-05-25 after a one-month dormant window).
- **Security boundaries.** JWT auth, per-org tenant scoping on every entity, SSRF guard on outbound URL fetchers, BCrypt cost 12 (OWASP 2024+), per-IP rate limiting on auth, append-only audit-log immutability trigger, encryption-at-rest for outbound API keys, HITL approval gates, bounded media-URL fetch (size + connect/read timeouts + redirect refusal).
- **Production profile.** `application-prod.properties` ships with fail-fast guards: CORS placeholder rejection at startup, required `JWT_SECRET` env var, locked-down actuator (no public exposure of details), Liquibase `validate`-only.
- **Observability primitives.** `/actuator/health`, Prometheus metrics endpoint, scheduled-method timing aspect, FinOps cost tracking + budgets, OTLP span export hooks, structured per-run audit events.
- **Schema discipline.** Liquibase migrations under `db/changelog/`, `ddl-auto=validate` (never `update`), per-changeset MD5 immutability.
- **UI.** React 19 + Vite 7 app with `vite build` producing static assets ready for any CDN/static host.

## Critical — cannot ship without

These are launch-day blockers. No amount of additional hardening on the existing code reduces this list.

1. **No Dockerfile for the app.** Only a docker-compose for Postgres + Redis + pgadmin (local dev). The JAR has no documented container image. Smallest possible PR.
2. **No deployment manifest.** No Kubernetes manifests, Helm chart, ECS task definition, Cloud Run config, App Runner, Fly machine — nothing. There is nowhere for the Dockerfile to go.
3. **No secrets management.** `JWT_SECRET` is read from env at boot. Where is that env set? No Vault / Secrets Manager / Parameter Store integration is documented or wired.
4. **No backup / restore strategy.** pgvector data is the entire product state. No documented backup pipeline, no tested restore.
5. **No frontend deploy target.** UI is `vite build`-ready but there is no documented host (S3 + CloudFront / Vercel / Netlify / served via Spring static resources / etc.).
6. **No production domain + TLS.** `application-prod.properties` literally contains the string `https://your-production-domain.com` as a CORS placeholder; the app refuses to boot in `prod` until this is replaced.

## Important — needed before the first ~10 real users

These don't block deployment, but they break the actual product experience and operational sanity once real users land.

7. **No email provider.** No SMTP, no SendGrid / Postmark / SES integration. Password reset flows, alert notifications to operators, user invites — all silently fail at the boundary.
8. **No error aggregation.** Prometheus surfaces metrics. There is no Sentry / Datadog / Rollbar / equivalent for exception tracking. First unhandled NPE in prod is invisible until a user complains.
9. **No log aggregation.** Production logs go to `/tmp/agm.log` (the local default). Nothing wires this to CloudWatch / Loki / Splunk / equivalent.
10. **No runbook.** `docs/admin/` is essentially empty. What does the on-call do when the DB is on fire, when an LLM provider returns 5xx storms, when a tenant's budget overruns by 1000×? Right now the answer is "read the code."
11. **No onboarding flow.** A new tenant registers via `POST /api/auth/register` and lands in an empty UI. No welcome flow, no sample agent, no first-agent wizard, no in-product orientation.
12. **No billing integration.** FinOps tracks cost beautifully via `FinOpsValuationRateEntity` and `BudgetPolicy`, but does not _charge_ anyone. If this is SaaS, Stripe (or equivalent) integration is missing.
13. **No SSO.** JWT username/password only. No Google/GitHub OAuth2, no SAML 2.0 for enterprise. Enterprise customers will block on this.

## Nice to have — post-launch

14. **Legal surface.** Terms of Service, Privacy Policy, DPA, and a user-facing GDPR right-to-be-forgotten export+delete flow. `DataRetentionService` and `ComplianceExportService` are the building blocks; the user-facing UX is not built.
15. **Multi-region / DR plan.** Today: single-region implied.
16. **CDN for UI static assets.** Vite produces hashed assets; serving them from a CDN is a config-only win once #5 is decided.
17. **Customer support inbox / ticketing.** Where does a customer report a bug? No documented path.

## Recommended phasing

| Phase | When | Items | Outcome |
|---|---|---|---|
| Phase 0 | This week | 1–6 | The app can actually be deployed somewhere |
| Phase 1 | Before any real users | 7–11 | Operations + UX won't catastrophically fail on first contact |
| Phase 2 | Before paid users | 12–13 | Customers can pay and large customers can buy |
| Phase 3 | Post-launch | 14–17 | Maturity, compliance, scale |

## Recommended next concrete task

**Item 1: write a Dockerfile.** It is the smallest, most obviously-blocking item, and it unlocks every subsequent infrastructure decision in Phase 0. Multi-stage build: Maven build + Eclipse Temurin 25 JRE base. Should land in one ~30-line PR.

After that, Phase 0 should be a focused 4–5 PR mini-sprint: Dockerfile → deploy-target choice (single-VM docker-compose is fine for v0) → secrets pipeline → backup script → FE host. Each is small in isolation and unsexy compared to the audit work, but each removes a real launch blocker.
