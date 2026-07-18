# Agent Manager — Minimum Launch Checklist

_Last updated: 2026-06-17_

The honest minimum to actually ship v1 — separated from the broader maturity work in [`agm-outstanding.md`](agm-outstanding.md). A successful "launch" here means: someone outside the team can sign up, log in, run an agent, and trust their data is reasonably safe.

Everything else is scale concerns — fix when they bite, not before.

## Required for any external launch

- [x] **Container image for the app** — `agent-manager/Dockerfile` (PR #1051) ✅
- [x] **Container image for the FE + reverse proxy** — `agent-manager-ui/Dockerfile` + `Caddyfile` (PR #1052) ✅
- [x] **Deployable stack** — `deploy/docker-compose.prod.yml` (PR #1052) ✅
- [x] **Secrets pipeline** — `.env.prod` mode 600 + `--env-file` (PR #1052) ✅
- [x] **HTTPS on a real domain** — Caddy auto-Let's-Encrypt when `DOMAIN` is set (PR #1052) ✅
- [x] **Backup script** — `deploy/scripts/backup.sh` + cron line in README (PR #1052) ✅
- [x] **Restore script** — `deploy/scripts/restore.sh` (PR #1052) ✅
- [x] **Operator runbook** — `deploy/README.md` (PR #1052) ✅
- [x] **Self-serve password reset + email transport** — `POST /api/auth/password-reset/{request,confirm}` + `spring-boot-starter-mail` + `RecordingMailService` test stub. Promoted from deferred-list because v1 needs self-serve reset. ✅
- [x] **Local-VM end-to-end dry-run** — 2026-05-27 (7 findings; #1057 + #1059) and a fuller boot on 2026-06-17 that built both images, booted the whole compose stack on a fresh volume, and exercised the Caddy edge. Surfaced + fixed 3 more real blockers: healthcheck probed auth-gated `/actuator/health` → 401 → web tier never started, and Caddy served the SPA shell for `/api/*` instead of proxying (both #1204); demo seed data loaded in the prod profile because the custom `SpringLiquibase` bean ignored `spring.liquibase.contexts` (#1205). Post-fix: Liquibase 001→111 clean, app+web healthy, SPA served, `/api` proxied, no demo data. Real-VM (cloud) dry-run still needed.
- [ ] **Real-VM end-to-end dry-run** — provision a small VM, run through the runbook, surface drift the local dry-run can't catch (TLS-ACME, DNS, cloud firewall).
- [ ] **DNS A-record pointed at the VM** — operator action; cannot ship without an address.
- [ ] **At least one LLM provider credential** — `POST /api/v1/provider-credentials` (per `(org, provider)`; OpenAI / Google / Anthropic) after first boot. Until one exists, agents can't respond (bar #4 below) and embedding search stays on the NoOp fallback. There is NO env/property fallback for LLM keys by design (`AbstractDynamicModelProvider.resolveApiKey`). For real RAG also re-embed via `POST /api/v1/admin/embeddings/backfill`.
- [ ] **SMTP credentials wired** — `SMTP_HOST` + `SMTP_USERNAME` + `SMTP_PASSWORD` + `MAIL_FROM_ADDRESS` in `.env.prod`. SendGrid / Postmark / Mailgun / SES all work; sender domain must be verified at the provider. Without these, the password-reset request endpoint accepts and silently fails to deliver (logged at ERROR).
- [ ] **Off-VM backup destination** — README has a cron-rsync example. Pick an S3-style bucket (or equivalent) and wire it. Local-only backups are one disk failure from extinction.
- [x] **One operator email reachable from the UI** — `mailto:` icon in the `DashboardLayout` footer. Operator sets `VITE_SUPPORT_EMAIL` at FE build time to override the placeholder `support@example.com`.

## Required for commercial launch (paid users / collecting payment info)

- [ ] **Terms of Service** — even a one-page generic version. Required if you take payment or collect PII beyond minimum.
- [ ] **Privacy Policy** — same threshold. Templates from termly.io / iubenda are fine for v1.
- [ ] **Billing integration** — Stripe (or equivalent). `FinOps*Service` tracks cost; no payment processing is wired. Only required if you're charging at launch.

## Not required (deferred from earlier overscoping)

These were on my original "Phase 1" list but are honestly *fix-when-they-bite*:

- **Error aggregation (Sentry/Datadog)** — `docker logs` is fine for the first ~10 users.
- **Log aggregation (CloudWatch/Loki)** — Docker default rotation (10 MB × 5 files) covers low-volume launch.
- **Onboarding flow** — if you can personally walk each early user through setup, no in-product wizard needed.
- **SSO** — username/password is universal; enterprises blocking on SAML/OIDC are not v1's target user.
- **Multi-region / DR / CDN / customer-support ticketing** — pure scale concerns; defer until you have scale.

## What "launch ready" looks like, concretely

When you can do all of these without an operator's help, v1 is shippable:

1. A new user opens `https://<your-domain>/` and sees a valid TLS cert (no browser warning).
2. They register, log in, and see the agent list (initially empty).
3. They create an agent via the UI (or an operator creates one for them).
4. They run that agent and get a response.
5. They can be told *"if this breaks, email X"* and X will actually receive that email.
6. Last night's database backup exists in two places (local volume + off-VM destination).
7. If the VM dies tonight, you can restore from the off-VM backup tomorrow.

That's the bar. Everything beyond is improvement, not launch-blocking.

## See also

- [`agm-outstanding.md`](agm-outstanding.md) — fuller Phase 0–3 inventory (the broader picture)
- [`../../deploy/README.md`](../../deploy/README.md) — operator runbook with the actual deploy procedure
