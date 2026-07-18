# Agent Manager UI Instructions

## 1. Project Overview
**Agent Manager UI** is the control plane for the high-performance "Agent Manager" runtime. It provides a dense, "Command Center" interface for monitoring and managing autonomous agents.

## 2. Tech Stack
- **Framework**: React 19 + Vite 7
- **Language**: TypeScript 5+ (Strict Mode)
- **Styling**: Tailwind CSS v4 + DaisyUI 5
- **State Management**: Zustand
- **Data Fetching**: TanStack Query
- **Routing**: React Router 6
- **Icons**: React Icons

## 3. Architecture & Directory Structure
The project follows a **Feature-Sliced Design** approach with a Shared Kernel.

```
src/
├── app/                  # App-wide routing, providers, and global config
├── features/             # Business features (e.g., agents, chat, settings)
│   ├── [feature]/
│   │   ├── components/   # Feature-specific components
│   │   ├── hooks/        # Feature-specific logic
│   │   └── api/          # API hooks/calls for this feature
├── shared/               # Reusable kernel shared across features
│   ├── components/
│   │   ├── ui/           # Dumb UI components (Card, Button, Input)
│   │   └── layout/       # Layout components (MainLayout, Sidebar)
│   ├── hooks/            # Shared hooks (useTheme, useMediaQuery)
│   ├── types/            # Global TS types
│   └── utils/            # Shared utilities (cn, formatter)
├── App.tsx               # Root component
└── main.tsx              # Entry point
```

## 4. Design System: Obsidian-Control
The UI implements the **Obsidian-Control** theme, designed for dark, high-contrast environments.

### Core Colors (Tailwind Config)
Do NOT hardcode hex values in components. Use the configured semantic names or specific Tailwind mappings defined in `ui-theme-specification.md`.

- **Background**: `bg-obsidian-base` (#0B0E14)
- **Surface**: `bg-obsidian-surface` (#11141B)
- **Elevated**: `bg-obsidian-elevated` (#1E293B)
- **Stroke**: `border-obsidian-stroke` (#334155)
- **Brand**: `text-agent-blue` (#3B82F6)

### Visual Language
- **Depth**: Use `border-obsidian-stroke` (1px) + `bg-obsidian-elevated` for cards.
- **Glow**: Active states should use subtle drop shadows (e.g., `shadow-[0_0_15px_rgba(59,130,246,0.1)]`).
- **Typography**: Inter (UI) + JetBrains Mono (Data/IDs).

## 5. Component Rules
### A. Compound Component Pattern
Complex UI components MUST use the Compound Component pattern.
```tsx
// Correct
<Card>
  <Card.Header title="Agent Status" />
  <Card.Body>Content</Card.Body>
</Card>
```

### B. Functional Purity
- **UI Components** in `shared/components/ui` must be "dumb" (presentation only).
- Data fetching logic belongs in `features/*/api` or custom hooks.
- Use `cn()` utility for class merging.

### C. State Management
- **Global UI State** (e.g., Sidebar open/close): Use Zustand.
- **Server State** (e.g., Agent list): Use TanStack Query.
- **Form State**: Use controlled components or specialized hooks.

### D. Forms and Inputs
- **Layout Alignment**: All input fields should be placed directly below their input field description (label). Keep the sizing and spacing of input fields and their descriptions properly aligned to maintain a clean, readable layout.
## 6. Coding Standards
- **Casing Rules**: All attribute types, properties, and interface fields MUST be strictly `camelCase`, never `snake_case`.
- **File Naming**: PascalCase for components (`AgentCard.tsx`), camelCase for hooks/utils (`useAgent.ts`).
- **Exports**: Use named exports (`export const Button...`), avoid default exports.
- **Props**: Define interfaces for props, extending HTML attributes where appropriate.
```tsx
interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary';
}
```

## 7. Development Workflow
- **Run Dev Server**: `yarn dev`
- **Lint**: `yarn lint`
- **Build**: `yarn build`
