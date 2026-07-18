# Hosting Agent Manager on Hetzner Cloud

A complete, reproducible plan to host the whole stack on **one Hetzner Cloud VM**
using Terraform for provisioning, cloud-init for hardening, and the existing
`deploy/docker-compose.prod.yml` for the app. Images are **built on the server**;
Postgres dumps are pushed **offsite to a Hetzner Storage Box** nightly.

> This layer sits on top of the app-level prod stack in `deploy/` (Dockerfiles,
> `docker-compose.prod.yml`, `.env.prod.example`, `backup.sh`/`restore.sh`). Read
> `deploy/README.md` for what each service does and the full secrets contract.

---

## 1. Architecture (single VM)

```
                    Internet
                       │  :80 / :443
            ┌──────────▼───────────┐   Hetzner Cloud Firewall: 22(you)/80/443/icmp
            │  Hetzner CPX41 VM    │   + host UFW + fail2ban
            │  Ubuntu 24.04        │
            │ ┌──────────────────┐ │
            │ │ caddy (web)      │ │  TLS via Let's Encrypt, serves SPA,
            │ │  :80 :443        │ │  reverse-proxies /api/* /mcp/* → app
            │ ├──────────────────┤ │
            │ │ app (Spring Boot)│ │  :8080 (internal only)
            │ ├──────────────────┤ │
            │ │ postgres (pgvec) │ │  :5432 (internal only)
            │ │ redis            │ │  :6379 (internal only)
            │ └──────────────────┘ │
            │  /var/lib/docker ───────► attached Hetzner Volume (snapshot-able)
            └──────────────────────┘
                       │ nightly pg_dump (ssh/rsync)
            ┌──────────▼───────────┐
            │ Hetzner Storage Box  │  offsite backups + retention
            └──────────────────────┘
```

Only ports 22/80/443 are reachable from outside. `app`, `postgres`, `redis` are
private on the Docker network. All container data lives on an attached volume
mounted at `/var/lib/docker`, so the server can be rebuilt without data loss.

**Sizing:** CPX41 = 8 vCPU / 16 GB / 240 GB (~€28/mo). The JVM is configured for
`MaxRAMPercentage=75`; 16 GB leaves real headroom alongside Postgres + Redis.
CPX31 (4/8, ~€16/mo) works for light load — Hetzner supports in-place resize
(power off → change type → power on; the data volume is untouched).

---

## 2. Prerequisites

On your **laptop**:
- [Terraform](https://developer.hashicorp.com/terraform/install) ≥ 1.5
- An SSH keypair (`~/.ssh/id_ed25519[.pub]`) — `ssh-keygen -t ed25519` if you don't have one
- A Hetzner Cloud **project** + **API token** (Console → project → Security → API tokens → *Read & Write*)
- A **domain** you control (for TLS). Any registrar / DNS host works.

You will need these **secrets** for `deploy/.env.prod` (generated below):
`JWT_SECRET`, `DB_PASSWORD`, `AGM_SECURITY_OUTBOUND_KEY_ENCRYPTION_KEYS`, and SMTP
credentials (for password-reset email). See `deploy/.env.prod.example`.

---

## 3. Provision the server (Terraform)

```bash
cd deploy/hetzner/terraform
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: set ssh_public_key_path and admin_ssh_cidr (your IP/32).
export TF_VAR_hcloud_token="<your-hcloud-token>"   # keep the token out of the file

terraform init
terraform apply        # review the plan, type yes
```

Terraform creates: the SSH key, a Cloud Firewall (22 restricted to your IP, 80/443
open), a data Volume, and the CPX41 server with the cloud-init bootstrap. On first
boot cloud-init (≈2–3 min) formats+mounts the volume at `/var/lib/docker`, creates
the unprivileged `deploy` user, hardens SSH (key-only, no root), installs Docker,
enables UFW + fail2ban + unattended-upgrades, and adds a 2 GB swapfile.

Note the `server_ipv4` output.

### DNS
Create records at your DNS host, then wait for them to resolve:
```
A     app.example.com    -> <server_ipv4>
AAAA  app.example.com    -> <server_ipv6>   # optional
```
Caddy needs the name resolving to the box **before** first deploy so it can issue
the Let's Encrypt certificate.

---

## 4. Put the source on the box

Images are built on the server, so it needs the repo. As the `deploy` user:

```bash
ssh deploy@<server_ipv4>
```

Then clone into `/opt/agent-manager` (created by cloud-init). For a **private**
repo, add a read-only deploy key first:
```bash
ssh-keygen -t ed25519 -f ~/.ssh/agm_deploy -N ""      # on the server
cat ~/.ssh/agm_deploy.pub                              # add to GitHub repo → Settings → Deploy keys (read-only)
GIT_SSH_COMMAND="ssh -i ~/.ssh/agm_deploy" git clone git@github.com:<org>/agent-manager.git /opt/agent-manager
```
(Or `rsync` the working tree up if you prefer not to use a deploy key.)

---

## 5. Configure secrets

```bash
cd /opt/agent-manager
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
```
Fill in `deploy/.env.prod`. Generate the secrets:
```bash
openssl rand -base64 32   # JWT_SECRET
openssl rand -base64 24   # DB_PASSWORD
openssl rand -base64 32   # AGM_SECURITY_OUTBOUND_KEY_ENCRYPTION_KEYS value → "1:<paste>"
```
Set `DOMAIN`, `APP_CORS_ALLOWED_ORIGIN_PATTERNS`, and `APP_PUBLIC_URL` to your
`https://app.example.com`. Fill SMTP (`SMTP_HOST/PORT/USERNAME/PASSWORD`,
`MAIL_FROM_ADDRESS`) — without it the app boots fine but password-reset emails
won't send. The prod profile **fail-fasts** if `JWT_SECRET`,
`AGM_SECURITY_OUTBOUND_KEY_ENCRYPTION_KEYS`, or a real CORS origin are missing.

> **LLM keys are NOT set here.** There is no env/`.env` fallback for provider
> keys by design. After the app is up, configure them per-org via
> `POST /api/v1/provider-credentials` (or the Admin → Provider Credentials UI),
> or set `apiKey` on the model row. Until a provider key exists, agent runs return
> 400 "No API key configured for provider …".

---

## 6. First deploy

```bash
cd /opt/agent-manager
deploy/hetzner/scripts/deploy.sh
```
This builds the images (several minutes the first time), starts
`web/app/postgres/redis`, runs Liquibase migrations on app boot, and waits for the
app healthcheck. Then:
```bash
curl -I https://app.example.com            # 200, valid TLS cert
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml ps
```
Caddy obtains the cert automatically on first request to the real domain. Create
the first admin user via the app's registration/login flow (see app docs).

---

## 7. Backups (offsite → Storage Box)

1. Order a **Storage Box** (Hetzner Robot → Storage Box) and enable **SSH support**
   (Settings). Note the user (`u123456`) and host (`u123456.your-storagebox.de`).
2. Authorize this server's key on the box (Storage Box SSH is port **23**):
   ```bash
   ssh-copy-id -p 23 u123456@u123456.your-storagebox.de
   ```
3. Install the nightly job:
   ```bash
   sudo cp deploy/hetzner/scripts/backup-offsite.sh /usr/local/bin/agm-backup
   sudo chmod +x /usr/local/bin/agm-backup
   ( crontab -l 2>/dev/null; echo "30 3 * * * STORAGEBOX_USER=u123456 STORAGEBOX_HOST=u123456.your-storagebox.de /usr/local/bin/agm-backup >> /var/log/agm-backup.log 2>&1" ) | crontab -
   ```
   It dumps Postgres, gzips, rsyncs offsite, and prunes dumps older than 14 days
   (override `KEEP_DAYS`). Restore with `deploy/scripts/restore.sh`.

**Also** snapshot the data Volume periodically (Hetzner Console → Volumes →
Snapshot, or automate via `hcloud volume create-snapshot`) for block-level DR.

---

## 8. Updates & rollback

- **Update:** `cd /opt/agent-manager && deploy/hetzner/scripts/deploy.sh`
  (pulls `main`, rebuilds, recreates, waits for health). Override the branch with
  `DEPLOY_BRANCH=release-x deploy/hetzner/scripts/deploy.sh`.
- **Rollback:** deploy a prior commit —
  `DEPLOY_BRANCH=<good-sha> deploy/hetzner/scripts/deploy.sh` (it `reset --hard`s
  to that ref and rebuilds). Schema note: Liquibase migrations are forward-only;
  a rollback across a destructive migration needs a DB restore from a dump.

---

## 9. Day-2 operations

```bash
cd /opt/agent-manager
C="docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml"
$C ps                      # status
$C logs -f app             # app logs
$C logs -f web             # caddy/access logs
$C exec app wget -qO- http://localhost:8080/actuator/health   # internal health (not exposed publicly)
$C restart app             # restart one service
$C exec postgres psql -U admin -d agent_manager               # DB shell
```
Resize: `terraform apply` after bumping `server_type`, or Console → power off →
rescale → power on. The attached volume and its data are untouched.

---

## 10. Security posture

- Inbound limited to 22 (your IP), 80, 443 at **both** the Hetzner Cloud Firewall
  and host UFW; fail2ban guards sshd; root SSH + password auth disabled.
- App/Postgres/Redis never bound to the host — private Docker network only.
- `/actuator/*` is deliberately **not** proxied by Caddy (no public management
  surface); reach it via `docker compose exec`.
- Secrets live only in `deploy/.env.prod` (chmod 600) on the box; the Terraform
  token stays in your shell env, not committed. LLM keys are encrypted at rest in
  Postgres (`provider_credentials`); A2A outbound keys are encrypted via
  `AGM_SECURITY_OUTBOUND_KEY_ENCRYPTION_KEYS`.
- Keep `terraform.tfvars` and `deploy/.env.prod` out of git.

---

## 11. Cost (approx, monthly)

| Item | Spec | ~€/mo |
|---|---|---|
| CPX41 server | 8 vCPU / 16 GB / 240 GB | 28 |
| Data Volume | 50 GB | 2.40 |
| Storage Box (BX11) | 1 TB | 3.80 |
| Traffic | 20 TB incl. | 0 |
| **Total** | | **~€34** |

CPX31 (4/8) instead of CPX41 drops the server to ~€16/mo.

---

## 12. Troubleshooting

- **Caddy TLS fails / 526:** DNS must resolve to the box before first request, and
  ports 80/443 must be open (they are, via the firewall). Check `… logs web`.
- **App won't boot (prod profile):** almost always a missing required secret —
  `… logs app` will name it (`JWT_SECRET`, CORS placeholder, encryption key,
  `GOOGLE_PROJECT_ID` empty). Fix `deploy/.env.prod` and re-run `deploy.sh`.
- **Agent run → 400 "No API key configured for provider":** expected until you add
  a provider credential (§5 note) — not a deploy failure.
- **cloud-init didn't finish:** `ssh deploy@…` then `cloud-init status --long` and
  `journalctl -u cloud-final`.
