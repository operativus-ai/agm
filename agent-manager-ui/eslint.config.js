import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

// FE forward-guard for the API-contract plan (docs/plans/agm-sync-apis.md Phase 4 T024).
// Symmetric to BE ControllerReturnTypeArchTest: prevents `ApiClient.{verb}(...)` calls from
// being committed without an explicit `<T>` generic. The typed BE record + typed FE call
// is the only way a BE schema rename surfaces as a compile error on the consumer side.
//
// SSE call sites (`ApiClient.stream(...)`) are exempt by design — EventSource carries
// discrete event/data strings, not a typed JSON body — and are not matched by this rule
// because `stream` is not in the verb regex.
const NO_UNTYPED_APICLIENT_CALL = {
  selector:
    "CallExpression[callee.type='MemberExpression']" +
    "[callee.object.name='ApiClient']" +
    "[callee.property.name=/^(get|post|put|delete|patch)$/]" +
    ":not([typeArguments])",
  message:
    'ApiClient.{get,post,put,delete,patch} calls must declare an explicit <T> generic ' +
    '(e.g. ApiClient.post<MyResponse>(...) or ApiClient.delete<void>(...)). ' +
    'See docs/plans/agm-sync-apis.md Phase 4 T024 for rationale. ' +
    'For SSE streams use ApiClient.stream() — that method is exempt by design.',
};

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      'no-restricted-syntax': ['error', NO_UNTYPED_APICLIENT_CALL],
      // Open-core direction rule (agm-core-oss-execution.md §4.5): Core code may reach
      // edition UI ONLY through the @ee/* manifest aliases (which resolve to empty stubs
      // in the Core build). Direct imports of the stub dir or a features-ee tree are banned.
      'no-restricted-imports': ['error', {
        patterns: [{
          group: ['**/features-ee-stub/*', '**/features-ee/*'],
          message: 'Import edition UI only via the @ee/routes and @ee/nav manifest aliases.',
        }],
      }],
    },
  },
])
