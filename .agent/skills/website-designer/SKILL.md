---
name: website-designer
description: Generates complete, production-ready websites — HTML pages, CSS stylesheets, landing pages, multi-page scaffolds, and responsive components — from natural-language design briefs.
tags: [html, css, web-design, responsive, accessibility, landing-page]
---

# Website Designer

## Purpose
Generate complete, browser-ready website artifacts from natural-language design briefs. Produces self-contained HTML5 pages, CSS stylesheets, responsive landing pages, multi-page site scaffolds, and individual UI components that require zero build tooling to render.

## Core Capabilities
1. **Single-Page Generation:** Create complete HTML5 documents with embedded CSS from a design description — landing pages, portfolios, dashboards, event pages, etc.
2. **CSS Design Systems:** Generate standalone stylesheets with custom properties, responsive breakpoints, typography scales, and component-level selectors.
3. **Landing Page Specialization:** Build conversion-optimized pages with hero sections, feature grids, testimonials, pricing tables, and call-to-action blocks.
4. **Multi-Page Scaffolding:** Produce coordinated sets of HTML files sharing a common navigation, header/footer, and CSS theme from a site map.
5. **Component Extraction:** Generate isolated, reusable UI components (navbars, card grids, footers, contact forms, pricing tables) as self-contained HTML/CSS snippets.

## Technical Standards
All generated code MUST follow these rules:

### HTML
- Semantic HTML5 elements (`<header>`, `<main>`, `<nav>`, `<section>`, `<article>`, `<footer>`).
- `<meta name="viewport" content="width=device-width, initial-scale=1.0">` on every page.
- Proper `<title>`, `<meta charset="utf-8">`, and `lang` attribute on `<html>`.
- Descriptive `alt` text on all `<img>` elements. Use placeholder services (e.g., `https://placehold.co/`) for demo images.

### CSS
- **Custom Properties first.** Define a `:root` block with color palette, font stacks, and spacing scale. Reference variables throughout — never scatter raw hex values.
- **Layout:** CSS Grid for page-level structure, Flexbox for component-level alignment. No float hacks.
- **Responsive:** Mobile-first `@media` breakpoints. At minimum: `640px` (sm), `768px` (md), `1024px` (lg).
- **Modern features:** `clamp()` for fluid typography, `gap` for spacing, `aspect-ratio` where applicable.
- No CSS frameworks or CDN links unless the user explicitly requests them (e.g., Tailwind, Bootstrap).

### Accessibility (WCAG 2.1 AA)
- Minimum 4.5:1 contrast ratio for body text, 3:1 for large text.
- Visible `:focus` outlines on all interactive elements.
- ARIA labels on icon-only buttons and non-semantic interactive elements.
- Skip-to-content link on multi-section pages.
- Keyboard-navigable menus and forms.

## Default Design System: "Obsidian-Control"
Unless the user provides a different brand or color scheme, all generated websites MUST use the **Obsidian-Control** aesthetic — a premium, developer-centric, deeply dark theme that feels like an advanced command center brought into a modern browser.
**MANDATORY:** Before designing any website components, you MUST read the authoritative visual specification located at `/docs/website/theme.md` to ensure exact parity with the Agent Manager UI.

### Color Palette
Define these as CSS custom properties in every `:root` block:
```css
:root {
  --obsidian-base: #0B0E14;      /* Deepest background — page body */
  --obsidian-surface: #11141B;   /* Structural panels — nav, sidebar, header */
  --obsidian-elevated: #1E293B;  /* Raised elements — cards, modals, dropdowns */
  --obsidian-stroke: #334155;    /* Borders and subtle dividers */
  --agent-blue: #3B82F6;         /* Primary brand accent — links, CTAs, focus rings */
  --active-green: #22C55E;       /* Success states, online indicators */
  --warn-amber: #F59E0B;         /* Warning states, pending indicators */
  --error-red: #EF4444;          /* Error states, destructive actions */
  --foreground: #F1F5F9;         /* Primary text (Slate-100) */
  --muted: #94A3B8;              /* Secondary/descriptive text (Slate-400) */
}
```
- Use `--agent-blue` as the **singular leading accent** slicing through the dark layout. All interactive highlights, focus states, and primary CTAs use this color.
- Semantic colors (`--active-green`, `--warn-amber`, `--error-red`) are used sparingly and only to indicate state — never as decoration.
- **Never scatter raw hex values.** Always reference the custom properties.

### Glassmorphism and Depth
- Floating elements (sticky headers, modals, notification cards) use translucency + backdrop blur: `background: rgba(17, 20, 27, 0.6); backdrop-filter: blur(8px);`
- Cards get heavy shadows (`box-shadow: 0 10px 25px -5px rgba(0,0,0,0.4)`) and subdued `--obsidian-stroke` borders to maintain crisp boundaries without harsh contrast.
- Layer hierarchy is communicated through background shade progression: `base` -> `surface` -> `elevated`.

### Typography
- Primary text: `--foreground` (#F1F5F9) — high-contrast white-on-dark for headings and body.
- Secondary text: `--muted` (#94A3B8) — contextual descriptions, labels, timestamps.
- **Monospace for technical data:** Metrics, system identifiers, code snippets, configuration values, and numeric data use `font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace`. This gives the output a precision-engineered, code-native feel.
- General UI text uses a clean sans-serif stack: `font-family: 'Inter', system-ui, -apple-system, sans-serif`.
- Limit to these 2 font families. Do not introduce additional typefaces.

### Design Philosophy
- **Clean over clever.** Favor whitespace, clear visual hierarchy, and restrained color use.
- **Immersive dark-first.** Dark mode is the default. If appropriate, include a `prefers-color-scheme: light` variant, but the primary experience is dark.
- **Purposeful motion.** Subtle transitions (`200–300ms ease`) on hover/focus states. No gratuitous animations.
- **Keywords that describe this aesthetic:** Immersive, Technical, Edge-to-Edge, Glass, Stealth, Control Center, Engineered, High-Contrast Accents.

## Operational Workflow
1. **Brief Analysis:** Parse the user's design brief. Identify page structure, content sections, color preferences, and any specific requirements (responsive breakpoints, accessibility needs, brand guidelines).
2. **Architecture Decision:** Determine whether the request maps to a single page, a stylesheet, a landing page, a multi-page scaffold, or an isolated component.
3. **Code Generation:** Produce complete, self-contained source code. Every file must work immediately when opened in a browser — no missing dependencies, no placeholder `TODO` comments, no partial implementations.
4. **Delivery:** Return the generated code in fenced code blocks with appropriate language tags (`html`, `css`). For multi-page sites, clearly label each file with its intended filename.

## Constraints
- **No partial output.** Every artifact must be complete and runnable. If a request is too large for a single response, break it into named files and deliver each one fully.
- **No framework assumptions.** Do not include React, Vue, Angular, or any JS framework unless the user explicitly requests it. Default output is vanilla HTML/CSS/JS.
- **No external dependencies.** Do not link to CDNs, Google Fonts, or third-party scripts unless the user specifically asks for them. Inline everything or use system font stacks.
- **No fabricated content.** Use realistic placeholder text (not Lorem Ipsum when avoidable — write contextually appropriate copy). Use placeholder image services for visuals.

## Trigger Phrases
- "Design a website for..."
- "Create a landing page for..."
- "Build me a portfolio site..."
- "Generate an HTML page that..."
- "I need a responsive navbar/footer/card grid..."
- "Create a multi-page site with..."
