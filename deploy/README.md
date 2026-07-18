# Agent Manager — Production Deploy (single-VM)

Operator runbook for the docker-compose-on-a-single-VM topology described in `docs/analysis/agm-outstanding.md`. This is v0 deploy infra — minimum viable for the first ~10 customers. Migrate to managed services (Cloud Run, ECS, etc.) when traffic + reliability requirements outgrow it.

## Topology

```
          ┌─────────────────────────────────────────────────────┐
internet  │  VM                                                  │
   ──443──┼─►  web (Caddy)  ──── /api/* + /mcp/* ───►  app       │
   ──80───┤    (auto-TLS)         (internal only)     (Spring)   │
          │       │                                      │       │
          │       │ SPA static                           │       │
          │       │                                      ▼       │
          │       │                              postgres + redis│
          │       └──► /srv (FE)               (named volumes)   │
          └─────────────────────────────────────────────────────┘
```

- Only ports **80 + 443** are exposed to the internet.
- `app`, `postgres`, and `redis` live on the internal compose network. They have no public IP and no port forwarding.
- Caddy obtains and renews Let's Encrypt certs automatically when `DOMAIN` is a real hostname pointed at this VM.

## Prerequisites

- A VM with at least **2 vCPU, 4 GB RAM, 40 GB SSD**. Bigger if you expect heavy multimodal LLM traffic (image inputs allocate ~10 MiB each before the bounded fetch caps).
- Docker Engine **27+** with `docker compose` v2.
- Ports 80 + 443 open in the cloud firewall. DNS A record pointing at this VM's public IPv4.
- Non-root user with `docker` group membership for ops work.

## First-time deploy

```bash
git clone https://github.com/sesker69/agent-manager.git
cd agent-manager
cp deploy/.env.prod.example deploy/.env.prod

# Required values (boot fails fast on any of these — the log names the offender):
#   DOMAIN                                      — DNS name pointed at this VM (":80" for plain-HTTP local runs)
#   JWT_SECRET                                  — openssl rand -base64 32
#   DB_PASSWORD                                 — openssl rand -base64 24
#   APP_CORS_ALLOWED_ORIGIN_PATTERNS            — https://<DOMAIN> (must NOT be the placeholder)
#   AGM_SECURITY_OUTBOUND_KEY_ENCRYPTION_KEYS   — "1:$(openssl rand -base64 32)"  (A2A key encryption-at-rest)
# Strongly recommended for a real launch:
#   SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD/MAIL_FROM_ADDRESS  — password-reset email (silently no-ops without)
#   ADMIN_BOOTSTRAP_ENABLED=true + ADMIN_BOOTSTRAP_{USERNAME,EMAIL,PASSWORD}  — first-boot only; auto-creates
#     the first admin (self-registration only grants ROLE_USER). Set back to false after the first boot.
vim deploy/.env.prod
chmod 600 deploy/.env.prod

# Build images + start. First build is ~5 minutes (Maven dependency download
# + Vite build); subsequent builds are <1 minute thanks to layer caching.
docker compose --env-file deploy/.env.prod \
               -f deploy/docker-compose.prod.yml up -d --build

# Watch the boot. Expect to see Liquibase migrations + 'Started AgentmanagerApplication'.
docker compose --env-file deploy/.env.prod \
               -f deploy/docker-compose.prod.yml logs -f app
```

The first request to `https://<DOMAIN>/` triggers Caddy's TLS-ACME flow; the cert lands in ~5 seconds and persists in the `caddy_data` named volume.

**After first boot — configure at least one LLM provider key.** Agents and RAG do nothing until a provider credential exists: log in as the bootstrapped admin and `POST /api/v1/provider-credentials` (per `(org, provider)`; OpenAI / Google / Anthropic). Until then, model pings fail and embedding search stays on the NoOp fallback (the app boots fine regardless — RAG is optional, see below).

## Update procedure

```bash
cd agent-manager
git pull origin main
docker compose --env-file deploy/.env.prod \
               -f deploy/docker-compose.prod.yml up -d --build
```

Rolling restart isn't built in at v0 — `up -d --build` recreates changed containers, which means ~10 s of 502s during app restart. For a true zero-downtime story you would either (a) front a second app replica with an HAProxy / Caddy load_balance directive, or (b) migrate to a PaaS that handles rolling deploys natively. See Phase 1 in `docs/analysis/agm-outstanding.md`.

### What to rebuild when a component changes

The stack is **four containers, two of which are our images** (`web` = React SPA + Caddy, baked into one image; `app` = Spring Boot). `postgres` and `redis` are stock images — never built. You can scope a rebuild to one service to limit the blip: `docker compose ... up -d --build <service>`.

| You changed | Rebuild | Command (`...` = `--env-file deploy/.env.prod -f deploy/docker-compose.prod.yml`) |
|---|---|---|
| Backend Java code, `pom.xml`, `application*.properties` | **`app`** image | `docker compose ... up -d --build app` |
| **Liquibase changelog** (DB schema) | **`app`** image — migrations run on the next app boot; no separate migrate step | `docker compose ... up -d --build app` |
| React FE code, `package.json`, `vite.config.ts` | **`web`** image | `docker compose ... up -d --build web` |
| **`Caddyfile`** (routing / security headers / TLS) | **`web`** image — the Caddyfile is *baked into* `agm-web`, not mounted, so a rebuild is required | `docker compose ... up -d --build web` |
| `deploy/.env.prod` (secrets / config values) | **nothing** — just recreate containers | `docker compose ... up -d` (no `--build`) |
| `deploy/docker-compose.prod.yml` (ports, env, healthchecks) | **nothing** — recreate | `docker compose ... up -d` |
| Postgres / Redis image version bump | re-pull the stock image | `docker compose ... pull postgres redis && docker compose ... up -d` |
| Firecrawl overlay (see below) | re-pull the Firecrawl images | `docker compose ... -f deploy/docker-compose.firecrawl.yml pull && ... up -d` |

> On the CI **pull-based** deploy (`release.yml` → `deploy-pull.sh`), you don't build on the box at all — CI builds `agm-app` + `agm-web` and the runner pulls the SHA-tagged images. The "rebuild" column above then maps to "which image CI rebuilt"; the box just `pull` + `up -d` via `IMAGE_TAG`.

## Backups

Add to root's crontab on the VM (one daily dump at 02:00 UTC):

```cron
0 2 * * * /opt/agent-manager/deploy/scripts/backup.sh >> /var/log/agm-backup.log 2>&1
```

Output: `deploy/backups/agm-YYYY-MM-DDTHHMMZ.dump`. Retention default is 7 daily; tune via `REPLICAS_DAILY` env var on the cron line.

### Restore

```bash
cd /opt/agent-manager
deploy/scripts/restore.sh deploy/backups/agm-2026-05-25T0200Z.dump
```

This is **destructive** — it drops the database and rebuilds from the dump. The script prompts for `restore` confirmation and stops the `app` service before touching anything.

### Off-VM backups

Local-only backups are a single hardware failure away from extinction. Sync the `deploy/backups/` directory off the VM at least daily:

```cron
30 2 * * * rsync -a --delete /opt/agent-manager/deploy/backups/ \
              backups@offsite:/backups/agent-manager/
```

For a v0 deployment, an S3 bucket with versioning + lifecycle rules is the cheapest defensible target.

## TLS / certs

Caddy handles obtaining + renewing Let's Encrypt certs automatically. Operator action is required only on:

- **DNS change** — update `DOMAIN` in `.env.prod`, restart the `web` service. Caddy requests a fresh cert for the new name.
- **Rate-limit hit** — Let's Encrypt limits 5 duplicate certs / week. If you keep recreating the `caddy_data` volume during testing you can hit this. Either use the staging endpoint (`acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` in the Caddyfile) or wait 7 days.

Inspect cert state:

```bash
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml \
               exec web caddy list-certificates
```

## Common ops

| Task | Command |
|---|---|
| Tail app logs | `docker compose ... logs -f app` |
| Shell into app | `docker compose ... exec app bash` |
| Run a psql query | `docker compose ... exec postgres psql -U admin agent_manager` |
| Health: public liveness (used by the container healthcheck) | `docker compose ... exec app wget -qO- localhost:8080/api/v1/health` |
| Health: deep component status (admin-auth required in prod; `/actuator/**` is NOT public) | `docker compose ... exec app curl -s -H "Authorization: Bearer <admin-jwt>" localhost:8080/actuator/health` |
| Check disk usage of named volumes | `docker system df -v` |
| Restart just the app (preserve web's open TCP) | `docker compose ... restart app` |

`...` is shorthand for `--env-file deploy/.env.prod -f deploy/docker-compose.prod.yml`.

## Optional: Firecrawl (web scraping)

The backend's web-scraping tools (`WebScraperTool` / `FirecrawlSearchTool`) call Firecrawl at `APP_FIRECRAWL_BASEURL` (default `http://firecrawl-api:3002`). **It is optional** — the app health-checks Firecrawl on use and degrades gracefully when absent (scraping is simply unavailable; nothing else is affected). Stand it up only if you need scraping.

`deploy/docker-compose.firecrawl.yml` is an overlay that self-hosts Firecrawl: `firecrawl-api` + a headless-browser `firecrawl-playwright` + its own `firecrawl-redis`, `firecrawl-rabbitmq`, and `firecrawl-nuq-postgres`. Compose-merge it on top of the prod stack so all services share the project network and `app` resolves `firecrawl-api`:

```bash
docker compose --env-file deploy/.env.prod \
  -f deploy/docker-compose.prod.yml \
  -f deploy/docker-compose.firecrawl.yml up -d
```

> **⚠️ Sizing.** This is a 5-service stack that wants **~6 vCPU / ~12 GB on top of the base app** — it does **not** fit a CPX31/CPX41 next to the app. Options: run it on a **bigger VM**, on a **separate VM** (set `APP_FIRECRAWL_BASEURL=http://<that-host>:3002` in `.env.prod`), or use a remote Firecrawl. Firecrawl **Cloud** (`https://api.firecrawl.dev`) needs a Bearer key, but the agm client sends no auth header today — so cloud needs a code change first; **self-hosted** (which runs `USE_DB_AUTHENTICATION=false`) works as-is.

Verify (from inside the app container, on the internal network):

```bash
docker compose --env-file deploy/.env.prod \
  -f deploy/docker-compose.prod.yml -f deploy/docker-compose.firecrawl.yml \
  exec app wget -qO- http://firecrawl-api:3002/ | head -c 80   # Firecrawl hello banner
```

Then the agent tool surfaces it: `GET /api/tools` lists `readWebpage` / `bulkIngestDocumentationSite` once Firecrawl is reachable. Tear down just the overlay with `... -f deploy/docker-compose.firecrawl.yml down` (omit `-v` to keep its Postgres volume). Images come from GHCR (`ghcr.io/firecrawl/*`, `:latest`) — pin to digests before relying on this in production.

## Troubleshooting

**App container restart-loops on boot.**
Most common: missing `JWT_SECRET`, placeholder `APP_CORS_ALLOWED_ORIGIN_PATTERNS`, missing `AGM_SECURITY_OUTBOUND_KEY_ENCRYPTION_KEYS`. The fail-fast guards are intentional and the error message in `docker compose logs app` will name the offending property.

**App container fails with `Google GenAI project-id must be set!` (legacy — should no longer occur).**
RESOLVED. The compose file defaults `GOOGLE_PROJECT_ID=unused`, which satisfies the Google AI embedding auto-config's `hasText` assertion, and a `NoOpEmbeddingModel` fallback lets the app boot with **no** real embedding provider (RAG/semantic-search simply returns nothing until a real model is configured). The 2026-06-17 prod-compose dry-run boots cleanly with no GCP credentials. To enable real RAG: add a Google `text-embedding-004` (768-dim) or OpenAI `text-embedding-3-*` provider credential (the latter is the seeded default via changeset 110) + re-embed via `POST /api/v1/admin/embeddings/backfill`. Only set a real `GOOGLE_PROJECT_ID` + ADC if you specifically use Vertex/Gemini embeddings.

**`502 Bad Gateway` from Caddy.**
The `app` container hasn't passed its healthcheck yet — Caddy proxies to upstream regardless of compose dependency state once started. Cold-boot the app takes ~30 s (Liquibase migrations on first deploy). Tail `docker compose logs app` and wait for `Started AgentmanagerApplication`.

**Postgres connection refused from app.**
The app fails fast on a missing DB. Check `docker compose ps postgres` — it should be `healthy`. If it's restart-looping, check `docker compose logs postgres` for disk-space or volume-permission errors.

**Caddy can't obtain a cert.**
Port 80 must be reachable from the public internet — Let's Encrypt's HTTP-01 challenge calls back to `http://<DOMAIN>/.well-known/acme-challenge/...`. Cloud-firewall blocks on port 80 are the single most common cause. Inspect: `docker compose logs web | grep -i acme`.

**I changed `DOMAIN` and now Caddy is requesting a cert in a loop.**
The old `caddy_data` volume references the previous domain's challenge state. Either `docker volume rm` the `caddy_data` volume (loses the old cert; new one provisions in seconds) or `docker compose exec web caddy reload` after editing the Caddyfile.

**The build is slow.**
First build downloads ~300 MB of Maven + npm dependencies. Subsequent builds re-use the cached layers — a code-only change is ~30 s. If a `pom.xml` or `package.json` edit is invalidating the deps layer on every build, that is expected behavior, not a bug.

## What this deploy intentionally does NOT do

These are deferred to Phase 1+ per `docs/analysis/agm-outstanding.md`. Each is a real production gap; the absence here is not an oversight:

- **No log aggregation.** App writes to stdout, captured by `docker logs` (rotated by Docker's default 10 MB × 5 file policy). For real ops, forward to CloudWatch / Loki / Splunk via `logging.driver` in the compose.
- **No error monitoring.** No Sentry / Datadog hook. First unhandled exception in prod is invisible until a user complains.
- **No SSO.** JWT username/password only.
- **No rolling deploys / zero downtime.** A code rebuild + restart blips ~10 s.
- **No horizontal scale.** Single VM. Add a load balancer + second replica + sticky sessions (or a shared Redis-backed session store, already present) when needed.
