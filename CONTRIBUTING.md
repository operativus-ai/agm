# Contributing to AGM

Thanks for your interest in contributing! AGM Core is developed in the open under
the [FSL-1.1-ALv2](LICENSE.md) license.

## Developer Certificate of Origin (DCO)

All contributions require a DCO sign-off. By signing off you certify the
[Developer Certificate of Origin 1.1](https://developercertificate.org/) — that
you wrote the contribution or otherwise have the right to submit it under this
project's license.

Add a sign-off to every commit:

```bash
git commit -s -m "fix(runs): ..."
```

which appends:

```
Signed-off-by: Your Name <you@example.com>
```

PRs with unsigned commits cannot be merged.

## Getting started

1. Follow the Quick Start in [README.md](README.md) to get Postgres/Redis/Jaeger
   and the app running locally.
2. Backend tests: `./mvnw test` from `agent-manager/` (unit suite; integration
   tests need Docker: `./mvnw test -Dexcluded.groups=`).
3. Frontend: `npm run build` from `agent-manager-ui/` (runs `tsc` + Vite).

## Pull request expectations

- **One branch per feature/fix**, targeting `main`.
- `./mvnw clean compile` and `./mvnw test` must pass before pushing (the
  incremental compile cache can mask overload-ambiguity errors — always `clean`).
- New top-level classes follow the existing `Domain Responsibility:` / `State:`
  Javadoc pattern.
- New background work implements `JobHandler` — no ad-hoc `@Async` methods.
- Cross-module features go through an interface in `core/registry/` (or an SPI in
  `core/spi/`), never a direct cross-module service injection.
- Schema changes are Liquibase changelog entries (Hibernate is `validate`-only).
  Never edit an already-merged changeset.
- Admin-gated endpoints must be added to the authz coverage manifest — the
  `AdminEndpointCoverageArchTest` will tell you exactly what to do if you forget.

## Security issues

Please do NOT open public issues for suspected vulnerabilities — see
[SECURITY.md](SECURITY.md).
