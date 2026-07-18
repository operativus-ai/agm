# syntax=docker/dockerfile:1.7
#
# Agent Manager — web tier (FE static assets + Caddy reverse proxy).
#
# Two-stage build:
#   1. builder — node:24-alpine runs `tsc -b && vite build` producing dist/
#   2. runtime — caddy:2-alpine serves the SPA AND reverse-proxies /api/* +
#                /mcp/* to the backend `app` service. Caddy handles TLS
#                automatically via Let's Encrypt; no manual cert dance.
#
# Build:  docker build -t agm-web:local agent-manager-ui/
# Run via compose only — the Caddyfile expects an `app` service on the same
# Docker network. Standalone `docker run` would 502 every API call.

# ── Stage 1: build the SPA ───────────────────────────────────────────────────
FROM node:24-alpine AS builder
WORKDIR /workspace

# Copy lockfile + package.json first so the npm-install layer caches when
# only source changes.
COPY package.json package-lock.json ./
# `npm install` rather than `npm ci`. The strict `ci` mode requires every
# platform variant of optional binary deps (e.g., @tailwindcss/oxide,
# @esbuild) to be present in the lockfile — but `npm install` on a macOS
# host writes the lockfile with only darwin entries, so a linux container
# build fails with "Missing: @tailwindcss/oxide-linux-x64-gnu from lock
# file." Using plain `npm install` resolves whichever platform the build
# is on. The lockfile is still consulted for non-optional deps so version
# determinism for the bulk of the tree is preserved. Switch back to
# `npm ci` if/when the team adopts a lockfile-regen step that includes
# all platform variants (or moves to pnpm).
RUN npm install --no-audit --no-fund --prefer-offline

COPY . .
# `tsc -b && vite build` per package.json. Produces ./dist/ with the SPA
# static assets + hashed filenames for cache busting.
RUN npm run build

# ── Stage 2: serve via Caddy ─────────────────────────────────────────────────
FROM caddy:2-alpine AS runtime

# Caddyfile is read from /etc/caddy/Caddyfile by default. We bake it in so
# the image is self-contained; the operator only needs to set $DOMAIN in
# the environment for TLS to land on the right cert.
COPY Caddyfile /etc/caddy/Caddyfile

# SPA static assets land at /srv (the path the Caddyfile points its
# `file_server` at).
COPY --from=builder /workspace/dist /srv

# Caddy listens on 80 (HTTP, redirects to HTTPS) and 443 (HTTPS, with the
# auto-provisioned Let's Encrypt cert). The compose maps these to the host.
EXPOSE 80 443
