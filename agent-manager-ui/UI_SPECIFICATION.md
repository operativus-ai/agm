# UI Component Architecture Specification

## Overview
The `agentmanager-ui` project adopts a **Compound Component Architecture** derived from the `operativus/frontend` DNA. This specification outlines the required patterns for all UI development.

## 1. Core Architecture

### Compound Components
Complex components MUST use the Compound Component pattern to allow flexible composition while enforcing styling constraints.

**Pattern:**
```tsx
// Good
<Card>
  <Card.Header>
    <Card.Title>Agent Status</Card.Title>
  </Card.Header>
  <Card.Body>...</Card.Body>
  <Card.Footer>...</Card.Footer>
</Card>

// Bad
<Card title="Agent Status" body="..." footer="..." />
```

### Functional Purity
*   Components should remain "dumb" (presentation only) when possible.
*   Logic hooks (e.g., `useAgentStatus`) should return data props, not UI elements.

## 2. Design System Integration

### Theme Variables
Do not use hex codes or hardcoded colors. Use the semantic variables defined in `src/index.css` and `tailwind.config.js`.

| Category | Variables | Usage |
| :--- | :--- | :--- |
| **Surface** | `--theme-background`, `--theme-card` | Main page backgrounds and card surfaces |
| **Content** | `--theme-foreground`, `--theme-muted` | Text and icons |
| **Automotive** | `--racing-red`, `--chrome-silver` | Feature-specific branding |
| **Status** | `--status-success`, `--status-error` | Feedback indicators |

### CSS Utility
Always use the `cn` utility for class merging to avoid collision issues.
```tsx
import { cn } from '@/shared/utils/cn';

<div className={cn("base-class", className)} />
```

## 3. Form System
All inputs must be wrapped in `FormFieldWrapper` (or use the unified `Form` components) to ensure consistent:
*   Labeling
*   Error Message Rendering
*   Accessibility Attributes (`aria-invalid`, `aria-describedby`)

## 4. Accessibility Standards
*   **Interactive Elements:** Must have `aria-label` if no text content exists.
*   **Focus:** All interactive elements must have visible focus states (handled by `btn` and `input` classes).
*   **Contrast:** Use the `base-content` utilities which automatically adjust for contrast against `base-100`/`base-200`.
