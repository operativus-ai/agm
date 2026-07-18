--liquibase formatted sql

--changeset agm:083-human-review-migration runOnChange:false
--comment: REQ-HR-6 — one-shot data migration from the soft-deprecated
--         workflow_steps columns (requires_confirmation, on_reject,
--         else_step_id) to the unified human_review JSONB. Idempotent:
--         rows that already have human_review populated are SKIPPED
--         (WHERE human_review IS NULL). Rows with no legacy signal
--         (requires_confirmation=false AND on_reject IS NULL AND
--         else_step_id IS NULL) are also skipped — nothing to migrate.
--
--         jsonb_strip_nulls() omits keys whose value is null so the
--         resulting JSONB is clean (no {"onReject": null, ...} noise).
--
--         Legacy columns are NOT dropped here — the dispatcher still
--         reads them when human_review is null. A separate cleanup PR
--         after FE consumers migrate will drop the legacy columns.
--
--         See docs/analysis/agm-human-review-unification.md §5 D3 + REQ-HR-6.
UPDATE workflow_steps
SET human_review = jsonb_strip_nulls(jsonb_build_object(
        'requiresConfirmation',
            CASE WHEN requires_confirmation THEN true ELSE NULL END,
        'onReject', on_reject,
        'elseStepId', else_step_id
    ))
WHERE human_review IS NULL
  AND (
        requires_confirmation = true
        OR on_reject IS NOT NULL
        OR else_step_id IS NOT NULL
      );
