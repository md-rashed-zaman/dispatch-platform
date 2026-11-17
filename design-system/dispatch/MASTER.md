# Dispatch design system (master)

The source of truth for every Dispatch screen: the dispatcher web app (`web/`) and the emails (`docs/design/email.md`, which uses the same tokens). Page files in `pages/` override this only where they say so.

## How it was made

With the ui-ux-pro-max plugin (2.15.0), then curated:

| Taken from the plugin | Not taken, and why |
|---|---|
| Style **Minimalism & Swiss** (query: B2B operations dashboard, density 8, variance 3, motion 3): clean, grid-based, high contrast, "enterprise apps, dashboards" | The "Real-time / operations landing" page pattern: this is an app, not a marketing page |
| Typography **"Dashboard Data"** (Fira Sans + Fira Code): "Code for data, Sans for labels" | The design-system query's Cinzel / Josefin Sans pairing (tagged "real estate, luxury") |
| Status semantics from the operations palette: operational green, attention amber, incident red | Its green brand color: the brand stays the blue already used in the emails (Logistics/Delivery palette), so email and app match |
| UX rules: live data labelled live only when a current source backs it, with update time and stale state; contextual live updates announced without moving focus; helpful empty states; confirm irreversible actions; visible focus everywhere; skeletons while loading | GSAP scroll reveals: nothing here scrolls into view as a story |
| shadcn stack rules: semantic `Table` (thead, tbody), never div grids for tabular data | |

## Principles

1. **The work order is the unit.** Every screen answers: what state is it in, what's waiting on me, what can I do now.
2. **Status reads at a glance.** A pill with a word and a color; the word carries the meaning, the color only helps (never color alone).
3. **Dense, not cramped.** Dispatchers scan many rows; 40 px table rows, 14 px table text, 16 px for reading and forms.
4. **Live is honest.** "Live" only while the stream is connected; otherwise "Reconnecting" with the time of the last update.
5. **Errors say what to do.** Problem Details from the API are shown in words next to what caused them.

## Tokens

Defined as CSS variables (`web/src/index.css`), mapped to Tailwind. All text pairs checked at 4.5:1 or better.

### Color, light

| Token | Value | Use |
|---|---|---|
| `--background` | `#F8FAFC` | page |
| `--card` | `#FFFFFF` | panels, table |
| `--foreground` | `#0F172A` | text (17.9:1 on card) |
| `--muted-foreground` | `#475569` | secondary text (7.6:1 on card) |
| `--border` | `#E2E8F0` | dividers, inputs |
| `--primary` | `#2563EB` | primary buttons (white text 5.2:1), links, focus ring |
| `--primary-hover` | `#1D4ED8` | |
| `--destructive` | `#B91C1C` | reject, cancel (white text 6.5:1) |

### Color, dark

| Token | Value |
|---|---|
| `--background` | `#0B1220` |
| `--card` | `#111A2E` |
| `--foreground` | `#E2E8F0` |
| `--muted-foreground` | `#94A3B8` (6.8:1 on card) |
| `--border` | `#1E293B` |
| `--primary` | `#3B82F6` for links and rings; buttons keep `#2563EB` with white text |

### Status (pill background / text, light; dark uses 15% tint of the hue on card with the 300 shade as text)

| Status | Label | Background | Text | Meaning |
|---|---|---|---|---|
| DRAFT | Draft | `#F1F5F9` | `#334155` | not visible to technicians |
| PUBLISHED | Finding technicians | `#DBEAFE` | `#1E40AF` | matching runs |
| ROUTED | Offered | `#E0E7FF` | `#3730A3` | waiting for an accept |
| ASSIGNED | Assigned | `#EDE9FE` | `#5B21B6` | a technician has it |
| IN_PROGRESS | In progress | `#FEF3C7` | `#92400E` | on site |
| SUBMITTED | Needs your approval | `#FFEDD5` | `#9A3412` | **waiting on the dispatcher** |
| APPROVED | Approved | `#DCFCE7` | `#166534` | done |
| PAID | Paid | `#D1FAE5` | `#065F46` | done, paid |
| CANCELLED | Cancelled | `#F1F5F9` | `#475569` | |

Each pill also has a 6 px dot in the text color; SUBMITTED's dot pulses (respects reduced motion).

### Type

- **Fira Sans** 400, 500, 600 for all text. **Fira Code** 400, 500 for ids, money, times, counts (`font-variant-numeric: tabular-nums`).
- Scale: 12 (labels, uppercase 0.04em), 14 (table, secondary), 16 (body, inputs), 20 (section titles), 24 (page title, 600). Line height 1.5 body, 1.25 titles.

### Space, shape, depth

- 4 px base: 4, 8, 12, 16, 24, 32, 48.
- Radius: 6 (inputs, buttons, pills: full), 10 (panels).
- Shadows: none on panels (border instead); `0 8px 24px rgb(15 23 42 / 0.12)` only on overlays (dialogs, menus).

### Motion

150 ms for hover and focus, 200 ms for panels; a changed table row flashes its background (primary at 8%) for 1.2 s. Everything off under `prefers-reduced-motion`.

## Components

| Component | Notes |
|---|---|
| App bar | 56 px: brand mark + "Dispatch", company name, live indicator, user menu (name, role, sign out) |
| Live indicator | dot + "Live" (green dot) / "Reconnecting" (amber) / "Offline" (gray) and "updated 12 s ago"; `aria-live="polite"` for state changes only |
| Status pill | see Status |
| Table | semantic `<table>`; sticky header; 40 px rows; whole row is a link to the detail; right-aligned money in Fira Code; on phones each row becomes a card |
| Filter tabs | All, Needs approval, Waiting for technician, In progress, Drafts, Done; counts are not shown (the API pages, it doesn't count) |
| Buttons | primary (blue), secondary (outline), destructive (red); 36 px high, 44 px on touch; loading state keeps the width |
| Dialog | confirm cancel; reject asks for a reason (required) |
| Toast | short success messages ("Published"); errors stay inline |
| Empty state | one sentence and the next action ("No work orders yet. Create the first one.") |
| Skeleton | rows and panels while loading; never a blank screen |

## Accessibility

Contrast 4.5:1 for text; visible 2 px focus ring (primary, 2 px offset) on everything; every icon button has a label; forms have visible labels and errors next to fields with `aria-describedby`; keyboard: tab order follows the layout, Enter opens a row, Esc closes dialogs.
