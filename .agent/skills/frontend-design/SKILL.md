---
name: frontend-design
description: Create distinctive, production-grade frontend interfaces strictly aligned with the project's 'Obsidian' design architecture.
tags: [react, typescript, ui, design-system]
---

# Frontend Design Architect

## Purpose
Generate production-ready UI designs and React layouts that adhere strictly to the Agent Manager "Obsidian" aesthetic and frontend tech stack.

## Architecture & Tech Stack Rules
You MUST adhere to the following when writing React code or designing interfaces:
- **Framework:** React 19 + TypeScript + Vite
- **Styling Core:** Tailwind v4 + DaisyUI 5.x.
- **Methodology:** Use global `@theme` variables. NEVER use inline styles. NEVER use unstructured hex codes if a CSS variable exists.

## The 'Obsidian' Aesthetic Guidelines
The application has a highly rigid, curated design language known as the "Modern Agentic High-Tech Framework/Application" aesthetic. You must NOT hallucinate "maximalist chaos" or pastel themes. You must execute this specific vision:

1. **Color Palette Enforcement**: 
   You must utilize the pre-defined CSS variables injected via `index.css`:
   - `--obsidian-base`: The deepest background color. Use for the root layout.
   - `--obsidian-surface`: Slightly elevated panels, sidebars, or navigation.
   - `--obsidian-elevated`: Highest surface layer (Cards, Modals).
   - `--obsidian-stroke`: Borders and subtle dividers.
   - `--agent-blue`: The primary brand accent color.
   - Wait to use semantic variables like `text-theme-muted` (mapped to `--theme-muted`) for secondary information to create sharp typographic hierarchy.

2. **Typography**:
   - High contrast UI. Keep headings bold and white (`text-white` or `text-theme-foreground`).
   - Use `prose` (Tailwind Typography) exclusively for long-form Markdown or generated chat bodies, allowing the pre-defined Obsidian prose variables to style the text automatically.

3. **Motion & Spatial Composition**:
   - Use restraint. Do not generate chaotic animations.
   - Focus on spatial breathing (generous padding/margins) to let the high-contrast elements stand out.
   - Rely heavily on DaisyUI's native structural classes (`card`, `card-body`, `btn`, `btn-primary`, `badge`) to ensure elements perfectly match the application's border-radius and shadow settings.

## Constraints
- **NO Cliched AI Aesthetics:** Avoid generic raw Tailwind outputs where every button is simply `bg-blue-500 rounded-md`. Use the DaisyUI primitive `btn btn-primary`.
- **Theme Preservation:** The UI design must look like it natively belongs inside the Agent Manager dashboard. It should look "Mechanical", "Refined", and "Subtle". 

## Output
When instructed to design or build a UI, emit clean Functional React Components that map perfectly directly to the rules above.