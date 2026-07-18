# UI Theme Specification: Obsidian-Control

## 1. Overview
**Theme Name:** Obsidian-Control  
**Inspiration:** Agentic Control Plane / High-Performance Monitoring  
**Aesthetic:** Polished dark stone, deep charcoal depth, and high-contrast functional accents.  

This theme is designed for high-density information environments, providing a "Command Center" feel for managing autonomous agents and complex data sets.

---

## 2. Color Palette (Hex Codes)

### Surface & Depth
Used to define the physical layers of the application.
* **Base (Background):** `#0B0E14` — Deepest obsidian; used for the main application body.
* **Surface (Sidebar/Nav):** `#11141B` — Dark stone; used for secondary navigation areas.
* **Elevated (Cards/Modals):** `#1E293B` — Lighter charcoal; used for "Active Agent" cards and interactive modules.
* **Stroke (Borders):** `#334155` — Subdued slate; used for subtle hair-line borders between components.

### Functional Accents
Used for status, actions, and brand identity.
* **Primary (Brand):** `#3B82F6` — "Agent Blue"; used for primary buttons, active states, and focus rings.
* **Success (Active):** `#22C55E` — Used for "Active" status indicators and uptime metrics.
* **Warning (Attention):** `#F59E0B` — Used for "Requires Attention" alerts.
* **Danger (Error):** `#EF4444` — Used for "Error" states and stop actions.
* **Information:** `#0EA5E9` — Used for system updates and toast notifications.

---

## 3. Typography
* **Primary Sans:** `Inter` or `Geist Sans` — For all UI elements, labels, and body text.
* **Mono:** `JetBrains Mono` or `Geist Mono` — For metrics, IDs, and code-related snippets.
* **Scale:**
    * **Heading (Dashboard):** 24px, Semibold, `#F8FAFC`
    * **Subheading (Card Titles):** 16px, Medium, `#F1F5F9`
    * **Data Labels:** 12px, Uppercase, Bold, `#94A3B8`
    * **Body:** 14px, Regular, `#CBD5E1`

---

## 4. Components & Effects

### Active Agent Cards
* **Background:** `#1E293B`
* **Corner Radius:** `8px` (Medium)
* **Border:** `1px solid #334155`
* **Shadow:** Subdued outer glow for active status (`box-shadow: 0 0 15px rgba(59, 130, 246, 0.1)`)

### Status Indicators
* **Active Glow:** Small circular indicator with a `2px` blur behind it.
* **Status Pills:** High contrast background with low-opacity fill (e.g., Success Pill: `bg-[#22C55E]/10 text-[#22C55E]`).

### Interactive Elements
* **Buttons:** Flat design with slight hover elevation. Use `#3B82F6` for "New Agent" or "Start" actions.
* **Inputs:** Darker background than cards (`#0F172A`) with a blue focus ring on interaction.

---

## 5. Implementation Guide (Tailwind CSS)
Add these to your `tailwind.config.js` to enable the theme in your React 19 project:

```javascript
module.exports = {
  theme: {
    extend: {
      colors: {
        obsidian: {
          base: '#0B0E14',
          surface: '#11141B',
          elevated: '#1E293B',
          stroke: '#334155',
        },
        agent: {
          blue: '#3B82F6',
          green: '#22C55E',
          amber: '#F59E0B',
          red: '#EF4444',
        }
      }
    }
  }
}