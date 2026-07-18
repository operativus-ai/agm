## What

<!-- One-paragraph summary of the change and why. -->

## Checklist

- [ ] Commits are DCO signed-off (`git commit -s`)
- [ ] `./mvnw clean compile` and `./mvnw test` pass locally
- [ ] Frontend touched → `npm run build` passes in `agent-manager-ui/`
- [ ] Schema touched → new Liquibase changeset (never edited an existing one)
- [ ] New admin endpoint → added to the authz coverage manifest
- [ ] No secrets, real PII, or customer data in code, tests, or fixtures
