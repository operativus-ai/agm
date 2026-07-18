# Agent Manager UI — Conventions

**Purpose:** reference for contributors adding new pages/features. Matches the patterns already in use across `src/features/*`. When in doubt, look at a recent, well-developed feature (e.g. `features/finops/`, `features/teams/`, `features/agents/`) and follow that shape.

**Related:** `docs/plans/agm-ui-buildout-plan.md` (U001 — this doc; U002–U480 — subsequent feature work).

---

## 1. Stack

| Layer | Choice |
|---|---|
| Framework | React 19 |
| Router | React Router v6 (`createBrowserRouter`) |
| Server state | TanStack Query v5 |
| Tables | TanStack Table v8 |
| Local store (client state) | Zustand v5 |
| Charts | Recharts |
| Icons | react-icons |
| CSS | Tailwind v4 + DaisyUI v5 |
| HTTP | Shared `ApiClient` wrapping `fetch` |
| SSE | `@microsoft/fetch-event-source` (via `ApiClient.stream`) |
| Bundler | Vite 7 |
| Tests | Vitest + @testing-library/react |

### Path aliases
See `tsconfig.app.json`. Use existing aliases; don't add new ones without discussion.

### Scripts
- Dev: `npm run dev` (Vite with `/api` + `/mcp` proxied to `localhost:8080`)
- Build: `npm run build`
- Tests: `npm test`
- Lint: `npm run lint` (if configured)

---

## 2. Routing

**Source of truth:** `src/app/router.tsx`. A single `createBrowserRouter([...])` call owns the entire route tree.

### Where to add a new route
Add an entry to the `children` array under the root authenticated branch:

```tsx
// src/app/router.tsx
{ path: 'schedules', element: <SchedulesPage /> },
{ path: 'schedules/:id', element: <ScheduleDetailPage /> },
```

Public routes (`/login`, `/register`) live at the top level, outside the `AuthGuard`-wrapped branch.

### AuthGuard
All authenticated routes are wrapped by `<AuthGuard>` at the root. No per-route `ProtectedRoute` component; if you need one, it's because you're adding route-level role gating (see §2.1).

### 2.1 Role-gated routes (new pattern — first introduced in U060+)
There is no `AdminRoute` component yet. When the buildout plan adds admin-only pages (`/settings`, `/components`, `/admin/pii-policies`), introduce:

```tsx
// src/shared/components/routing/AdminRoute.tsx
export const AdminRoute: React.FC<{ children: ReactNode }> = ({ children }) => {
  const { user } = useAuth();
  if (!user?.roles?.includes('ADMIN')) return <Navigate to="/" replace />;
  return <>{children}</>;
};
```

Then in `router.tsx`:

```tsx
{ path: 'settings', element: <AdminRoute><SettingsPage /></AdminRoute> },
```

Backend `@PreAuthorize("hasRole('ADMIN')")` remains the security enforcement — `AdminRoute` only gates navigation affordance.

---

## 3. Navigation & Layout

**Source:** `src/features/dashboard/layouts/DashboardLayout.tsx` wraps all authenticated routes. Nav entries are currently hardcoded.

### Adding a nav entry
Find the nav-item array in `DashboardLayout.tsx` and add the new `{label, path, icon}` entry. Keep alphabetical or grouped by domain — match surrounding style.

### Admin sub-nav
No separate admin sub-nav today. When adding `/admin/*` pages (U080 PII Policies, U060 Settings, U070 Components), introduce a collapsible "Admin" section in the sidebar that renders only when `user.roles.includes('ADMIN')`. Prefer one section over inline scattered entries.

---

## 4. Shared UI Primitives

Available under `src/shared/components/ui/`:

- **`Button`** — variants: `primary | secondary | ghost | outline | danger | link | destructive`. Sizes: `xs | sm | md | lg | xl`.
- **`Input`, `Textarea`, `Select`** — controlled via `onChange` or `onValueChange`. Icon slots (`startIcon`, `endIcon`).
- **`Dialog`** — props: `{isOpen, setIsOpen, title, onConfirm?, onCancel?, severity?, canBeCanceled?, shouldCloseOnConfirm?}`. Wraps content in `ModalContainer`.
- **`DataTable`** — TanStack Table wrapper; supports client- and server-side pagination (`manualPagination: true` with `pageIndex/pageSize/totalElements/onPageChange`).
- **`Tabs`** — shared Tabs primitive.
- **`Card`, `Badge`, `Checkbox`, `Radio`, `Alert`** — standard primitives.
- **`FormFieldWrapper`** — wraps labelled form inputs and renders `error` + `helperText`.
- **`MultiSelectDropdown`, `SearchableSelect`, `TagInput`** — complex form controls.
- **`MarkdownEditor`, `MarkdownRenderer`** — for rich text.
- **`PageContainer`, `PageHeader`** — layout wrappers; use on every page.
- **`Skeleton`** — loading-state primitive.
- **`EmptyState`** — for "no items yet" / zero-results panels.

### Missing primitives (add as needed — earliest consumer owns creation)
- **`ConfirmDialog`** — a thin wrapper around `Dialog` that preconfigures confirm/cancel + destructive styling. U040 (agent credentials delete) is the first real need.
- **`Toast` / `Notification`** — no library installed. U010 (approvals resolve success/failure) is the first real need. Recommend [`sonner`](https://sonner.emilkowal.ski/) (small, unopinionated, accessible).
- **`ErrorBoundary`** — wrap `<App>` with a React error boundary. U001 or a lightweight follow-up.

---

## 5. API Client (`src/shared/api/client.ts`)

### Shape
```ts
ApiClient.get<T>(endpoint: string): Promise<T>
ApiClient.post<T>(endpoint: string, body?: unknown): Promise<T>
ApiClient.put<T>(endpoint: string, body?: unknown): Promise<T>
ApiClient.delete<T>(endpoint: string): Promise<T>
ApiClient.stream(endpoint: string, { onMessage, onError?, onClose? }): AbortController
```

### Base URL
`/api` is prepended automatically. **Always pass paths relative to `/api`**, never the full URL. Examples:
- `ApiClient.get('/v1/teams')` → hits `http://host/api/v1/teams`
- `ApiClient.get('/agents')` → hits `http://host/api/agents`

### Authentication
Bearer JWT from `localStorage[STORAGE_KEYS.AUTH_TOKEN]` injected into every request. On 401 the client clears the token and redirects to `/login`.

### Error shape
Thrown as `ApiError extends Error` with `status: number` and optional `fields: Record<string, string>` (for field-level validation errors). Catch and branch on `status`:

```ts
try {
  await schedulesApi.create(input);
} catch (e) {
  if (e instanceof ApiError && e.status === 409) toast.error('Conflict');
  else throw e;
}
```

### SSE / streaming
Use `ApiClient.stream()` — NOT `new EventSource(url)`. The client wraps `@microsoft/fetch-event-source` which handles auth headers; raw `EventSource` can't send `Authorization`. When introducing a new stream, also wrap it in a `useEventSource` hook (U005) that enforces `useEffect` cleanup via the returned `AbortController`.

### Tracing
Every request carries a `traceparent` header (OpenTelemetry W3C context). No action needed — generated automatically.

---

## 6. Feature Folder Structure

```
src/features/<domain>/
├── api/
│   └── <domain>Api.ts       # BASE + ApiClient calls
├── pages/
│   └── <Domain>Page.tsx     # Route-level components
├── components/
│   └── <Domain>*.tsx        # Reusable pieces
├── hooks/
│   └── use<Domain>*.ts      # TanStack Query hooks
├── store/                   # (optional) Zustand slice if cross-component local state needed
│   └── <domain>Store.ts
└── types/                   # (optional) domain types
    └── index.ts
```

**Representative example:** `features/finops/` (clean hook pattern) or `features/agents/` (covers forms, modals, admin).

### Page template
```tsx
// features/<domain>/pages/<Domain>Page.tsx
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { LuCalendarClock } from 'react-icons/lu';

export const SchedulesPage: React.FC = () => {
  return (
    <PageContainer>
      <PageHeader icon={LuCalendarClock} title="Schedules" subtitle="Cron-based agent runs." />
      {/* body */}
    </PageContainer>
  );
};
```

### API module template
```ts
// features/<domain>/api/<domain>Api.ts
import { ApiClient } from '../../../shared/api/client';

const BASE = '/v1/schedules';

export class SchedulesApi {
  static async list(page = 0, size = 20): Promise<PageResponse<Schedule>> {
    return ApiClient.get(`${BASE}?page=${page}&size=${size}`);
  }
  static async get(id: string): Promise<Schedule> {
    return ApiClient.get(`${BASE}/${id}`);
  }
  static async create(req: ScheduleCreateRequest): Promise<Schedule> {
    return ApiClient.post(BASE, req);
  }
}
```

**`BASE` must match the backend `@RequestMapping` prefix exactly** minus the `/api` root. Parity audit tooling relies on this invariant — grep tools resolve `const BASE = '...'` for cross-checking with backend routes.

### Hook template
```ts
// features/<domain>/hooks/useSchedules.ts
export const useSchedules = (page = 0, size = 20) => {
  return useQuery({
    queryKey: ['schedules', 'list', page, size],
    queryFn: () => SchedulesApi.list(page, size),
    staleTime: 30_000,
  });
};

export const useCreateSchedule = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: SchedulesApi.create,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['schedules'] }),
  });
};
```

---

## 7. Forms

No form library installed. Use **inline `useState` + manual validation** plus `<FormFieldWrapper>` for per-field error rendering. Representative pattern: `features/agents/components/AgentFormModal.tsx`.

### Standard pattern
```tsx
const [values, setValues] = useState<FormShape>(initial);
const [errors, setErrors] = useState<Record<string, string>>({});

const validate = () => {
  const e: Record<string, string> = {};
  if (!values.name.trim()) e.name = 'Required';
  if (!isValidCron(values.cron)) e.cron = 'Invalid cron expression';
  setErrors(e);
  return Object.keys(e).length === 0;
};

const submit = async () => {
  if (!validate()) return;
  try {
    await createMutation.mutateAsync(values);
    setIsOpen(false);
  } catch (e) {
    if (e instanceof ApiError && e.fields) setErrors(e.fields);
  }
};

return (
  <FormFieldWrapper label="Name" error={errors.name}>
    <Input value={values.name} onChange={(v) => setValues({ ...values, name: v })} />
  </FormFieldWrapper>
);
```

### When to reach for a library
Forms with 10+ fields, complex cross-field validation, or dynamic field arrays are candidates for React Hook Form + Zod. **Do not add the library opportunistically**; introduce it as its own task (with existing form migration in scope) and document it in this file.

---

## 8. Modals & Dialogs

Use the shared `<Dialog>`. Open state is local (`useState`), opened by the caller. No global modal manager.

```tsx
const [isOpen, setIsOpen] = useState(false);

<Button onClick={() => setIsOpen(true)}>Delete</Button>

<Dialog
  isOpen={isOpen}
  setIsOpen={setIsOpen}
  title="Delete schedule?"
  severity="danger"
  onConfirm={async () => {
    await deleteMutation.mutateAsync(id);
    setIsOpen(false);
  }}
  onCancel={() => setIsOpen(false)}
/>
```

For destructive actions, follow the **"type-to-confirm"** pattern: require the user to type the item's name or alias before the confirm button enables. Introduced in U040 (agent credentials delete).

---

## 9. Tables

Use `<DataTable>` (TanStack Table v8 wrapper). Server-side pagination is the default for lists that can grow unbounded.

```tsx
<DataTable
  columns={columns}
  data={data?.content ?? []}
  manualPagination
  pageIndex={page}
  pageSize={pageSize}
  totalElements={data?.totalElements ?? 0}
  onPageChange={setPage}
/>
```

### Sorting / filtering
Client-side sorting via TanStack's `getSortedRowModel`; server-side sorting requires `manualSorting: true` + passing `sorting` state to the query. Filtering similarly — keep it server-side for list endpoints that paginate.

### Column visibility
Not currently wired. If needed, add a toolbar with shadcn-style column-visibility dropdown (introduce in a dedicated task).

---

## 10. Data Fetching (TanStack Query)

### Query keys
Shape: `['<domain>', '<action>', ...args]`. Examples:
- `['schedules', 'list', page, size]`
- `['schedules', 'detail', id]`
- `['finops', 'activeBurnRates']`

### Defaults
Set in `src/main.tsx` (or similar QueryClient init):
- `refetchOnWindowFocus: false` (global)
- `staleTime: 0` (unless overridden per query)

Override `staleTime` per-query:
- **Telemetry / live data** — `refetchInterval: 5_000`, `staleTime: 0`
- **List pages** — `staleTime: 30_000`
- **Historical / aggregate** — `staleTime: 5 * 60_000`
- **Config / settings** — `staleTime: Infinity` (invalidate on mutation only)

### Mutations
Always invalidate by prefix on success:
```ts
onSuccess: () => qc.invalidateQueries({ queryKey: ['schedules'] });
```

Optimistic updates are allowed but document the rollback path in `onError`.

### Polling guidance
- **OK:** `refetchInterval >= 30_000` on aggregates; `5_000` on single live-telemetry endpoints.
- **Avoid:** per-row polling in `forEach` / `.map()` loops. One such N+1 exists today in `ActiveRunsTracker.tsx` (tracked as U007 + backend batch endpoint).
- **Prefer SSE over polling** when the backend supports it (e.g. `/api/v1/runs/{runId}/events`).

---

## 11. Error Handling

### Today's state
- `ApiClient` handles 401 → clear token + redirect to `/login`.
- Forms surface `ApiError.fields` per-field via `FormFieldWrapper`.
- Non-form errors currently propagate as thrown `ApiError` — no central toast handler.

### Target pattern (to land alongside U010 toast library introduction)
1. Wrap `<App>` in `<ErrorBoundary>` that renders a friendly fallback.
2. Install `sonner` and expose `toast.success()` / `toast.error()` from `src/shared/toast.ts`.
3. Mutation `onError`: `toast.error(err.message)` unless `err.fields` (form error — render inline).
4. Query failures: TanStack Query's global `onError` routes to toast.

---

## 12. Styling

### Tailwind + DaisyUI + Obsidian theme
- Tailwind v4 classes everywhere — no CSS modules, no styled-components.
- DaisyUI provides `btn-*`, `badge-*`, `alert-*` primitives; our `Button` wraps them with Obsidian-theme overrides.
- **Obsidian theme tokens** (applied as Tailwind classes, not CSS variables):
  - `bg-obsidian-base`, `bg-obsidian-elevated`, `bg-obsidian-card`, `border-obsidian-stroke`
  - `text-theme-foreground`, `text-theme-muted`
  - Domain accents: `bg-agent-blue`, `bg-error-red`, `bg-success-green`
  - Opacity: `bg-error-red/10`, `border-obsidian-stroke/50`

### When to add theme tokens
If you find yourself repeating a color combination 3+ times, add a new token via a shared class in the feature's styles and reference it. Don't invent one-off hex codes inline.

### Light/dark mode
Not currently supported. Defer until product requirement.

---

## 13. Testing

Vitest + @testing-library/react. Setup: `src/setupTests.ts`. Representative tests: `Badge.test.tsx`, `Button.test.tsx`.

### What to test
- **Primitives** — prop-driven rendering and event handlers.
- **Hooks** — TanStack Query hooks with a test `QueryClientProvider` wrapper.
- **Pages** — smoke test that the page renders without errors given a mocked `ApiClient`.

### What not to test (yet)
- E2E / visual regression — not wired. Playwright is an open question for a later session.

### Running
- `npm test` — watch mode.
- `npm test -- --run` — single run (for CI).

---

## 14. Environment & Config

### Vite proxy (dev)
`vite.config.ts` proxies `/api` and `/mcp` to `localhost:8080`. Running `npm run dev` + the backend locally gives a fully wired app.

### Env vars
Any env-driven config uses `import.meta.env.VITE_*`. Do NOT add runtime config via `window.__CONFIG__` or similar — Vite handles it via env.

### Auth
JWT in `localStorage[STORAGE_KEYS.AUTH_TOKEN]`. `AuthContext` exposes `user` + `login()` + `logout()`. Login page at `/login` POSTs to `/api/auth/login` and stores the returned token.

---

## 15. Known gaps (to be addressed by U002 and throughout the plan)

| Gap | First consumer task |
|---|---|
| No `ConfirmDialog` — reach for `Dialog` today | U040 (agent credentials delete) |
| No toast library — errors only via throw | U010 (approvals resolve feedback) |
| No `ErrorBoundary` wrapping `<App>` | U001 follow-up or U002 |
| No `AdminRoute` for route-level role gating | U060 (Settings), U070 (Components), U080 (PII Policies) |
| No admin sub-nav | U060–U080 cohort |
| No `useEventSource` hook — `evaluationApi.streamRun` returns bare `EventSource` | U005 |
| 3 distinct SSE patterns in codebase | U005 (consolidation) |
| 3 duplicate `*Api.ts` pairs (agents vs admin, agents vs chat, monitoring ×2) | U009 |
| Mutation pattern not documented in code samples | U010 (first new mutation task) |

Each gap is a tracked task in `docs/plans/agm-ui-buildout-plan.md` (buildout plan at repo root, gitignored).

---

## 16. When in doubt

1. Find a well-developed feature and copy its shape (`features/finops/` is a good TanStack Query example; `features/agents/` covers forms + modals + admin).
2. Check this doc for the convention.
3. If the convention doesn't exist yet: introduce it here **in the same PR** that first needs it, so future readers find the rationale next to the pattern.
4. Don't introduce a second pattern for something we already have (e.g. don't `new EventSource()` when `ApiClient.stream()` exists).

Questions or unclear conventions: bring it up on the PR; update this doc when consensus lands.
