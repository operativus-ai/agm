# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately to **security@operativus.ai**.
Do not open public GitHub issues for security reports.

Include, where possible: an affected version/commit, reproduction steps or a
proof of concept, and the impact you believe it has. We aim to acknowledge
reports within 3 business days.

## Supported versions

Security fixes land on `main` and the most recent release line.

## Scope notes

- AGM is a multi-tenant control plane: cross-tenant data exposure (IDOR,
  unscoped queries, vector-store leakage across `org_id` boundaries) is always
  in scope and treated as highest severity.
- Prompt-injection reports are in scope where they cross a *platform* security
  boundary (tool-tier escalation, PII-redaction bypass, approval/HITL bypass) —
  not where they only affect a single agent's own output quality.
- The advisor safety chain (PII anonymization, content safety, tier escalation
  guards) ships in Core deliberately: safety is not a paid feature. Reports
  against it are welcome.
