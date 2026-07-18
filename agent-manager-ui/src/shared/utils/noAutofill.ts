/**
 * Spread onto a secret / API-key `<input>` (or the shared `Input`) to stop browsers and
 * password managers from autofilling a saved credential into it. Chrome ignores
 * `autoComplete="off"` for `type=password` fields, so `"new-password"` is used to suppress the
 * existing-credential autofill; the `data-*` opt-outs cover 1Password / LastPass / Bitwarden.
 *
 * Without this, a saved password can be injected into an API-key field and silently sent to the
 * provider — surfacing as a confusing 401 with a non-key value (see provider-credentials).
 *
 * Usage: `<input type="password" {...NO_AUTOFILL} … />`
 */
export const NO_AUTOFILL = {
  autoComplete: 'new-password',
  'data-1p-ignore': true,
  'data-lpignore': 'true',
  'data-form-type': 'other',
};
