---
name: react-ui-generator
description: Generates modern React UIs using TypeScript, Tailwind, and DaisyUI perfectly aligned with Agent Manager's feature-sliced architecture.
tags: [react, typescript, ui, streaming, architecture]
---

# React UI Generator

## Purpose
Generate production-ready React 19 UI components tailored for the Agent Manager ecosystem. You are the definitive authority on building functional elements, wiring data mutations, and managing complex streaming LLM state.

## Core Directives

You MUST adhere to the following stack constraints:
- **Framework:** React 19.x + Vite
- **Language:** TypeScript 5.9+ (Strict, NO `any`)
- **Styling:** Tailwind CSS 4.x + DaisyUI 5.x 
  - *DO NOT USE shadcn/ui. Rely exclusively on DaisyUI utility classes and raw Tailwind.*
- **State Management:** Zustand 5.x (Global Store) and React Query (Server State)

## Project Architecture & Data Fetching

When generating comprehensive pages or functional logic, you must strictly follow the monorepo's structural patterns:

1. **Feature-Sliced Architecture:** Components belong in their respective feature folders (e.g., `src/features/evaluations/components`). Shared global components belong in `src/shared/ui`. Never build monolithic pages; decompose everything into logical sub-components.
2. **The `ApiClient` Mandate:** NEVER hallucinate raw `fetch()` or `axios()` calls for standard REST endpoints. You MUST use the authoritative `ApiClient.ts` (which natively handles 401s, JWT lifecycle, and context tracing).
3. **React Query Abstraction:** NEVER write `useQuery` or `useMutation` directly inside a UI presentation component. Always abstract server-state fetching into custom hooks (e.g., `useEvaluations.ts` or `useMutationHook.ts`) that export the necessary data and loading states.
4. **Type Strictness:** TypeScript `interface` or `type` definitions must perfectly reflect the corresponding Spring Boot Java `Record` DTOs to guarantee contract synchrony.

## Component Build Rules
- Use Functional Components + Hooks exclusively.
- Utilize React 19's native hooks (e.g., prefer parsing forms via `useActionState` instead of archaic manual `useState` boilerplate).
- Use `clsx` and `tailwind-merge` for dynamic `className` construction.
- Abstract generic UI elements using DaisyUI standard semantic classes (`btn`, `btn-primary`, `card`, `card-body`, `badge`) to maintain project aesthetic consistency. Leave visual custom thematic identity to `@frontend-design`.

## Real-Time Streaming (ChatGPT Style)
If generating components that interact directly with the generative AI Engine stream:
- **DO NOT** use synchronous standard `fetch().then()` blocks that freeze the UI until the entire LLM response completes.
- **DO NOT** use the basic `EventSource` API, as Agent Manager requires advanced `POST` requests and Authentication headers for generative evaluation runs.
- **DO** implement real-time streaming natively:
  - Use native `fetch` (with headers proxying the Auth context) paired with `response.body?.getReader()` and a `TextDecoder` to manually parse Server-Sent Events (`text/event-stream`) chunk-by-chunk.
  - Manage progressive UI state cumulatively (e.g., `setResponse(prev => prev + newChunk)`) for a typewriter effect.
  - Always bind an `AbortController` to the request, exposing a cancellation function tightly coupled to a "Stop Generation" UI button.