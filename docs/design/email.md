# Email design

The first visual piece of Dispatch: the emails a company's dispatch team receives. The same tokens will seed the dispatcher dashboard. Template: `app/src/main/resources/templates/email/work-order-update.html` (and `.txt`).

## Direction

Calm and precise: an operations tool for banks and retailers, read between other work. One clear status, the facts, one action. No images (many clients block them), no web fonts (most clients strip them).

Sources: colors and typography from the ui-ux-pro-max plugin (Logistics/Delivery palette, "Minimal Swiss" Inter pairing). The plugin's suggested glassmorphism style and landing-page pattern were not used: neither renders in email clients. The email layout rules below are general email-safe practice; the plugin had no email guidelines.

## Tokens

| Token | Light | Dark (clients that support it) | Use |
|---|---|---|---|
| primary | `#2563EB` | `#60A5FA` (brand text) | Brand mark, CTA |
| on-primary | `#FFFFFF` | | CTA label (5.2:1) |
| background | `#F8FAFC` | `#0F172A` | Page, details block |
| card | `#FFFFFF` | `#1E293B` | Card |
| foreground | `#1E293B` | `#F1F5F9` | Headline, values |
| body | `#334155` | `#F1F5F9` | Paragraphs (10.4:1) |
| muted | `#475569` | `#94A3B8` | Labels, footer (7.2:1) |
| border | `#E2E8F0` | `#334155` | Card, dividers |
| success | `#166534` on `#DCFCE7` | | "Technician assigned" pill (6.5:1) |
| attention | `#92400E` on `#FEF3C7` | | "Waiting for approval" pill (6.4:1) |

Type: `Inter, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif`; monospace for references. Sizes: headline 22/30 semibold, body 16/24, labels and footer 13/18-20. Radius: card 12, details 8, CTA 8, pill full.

## Layout

- 600 px max, centred, tables with inline styles (Outlook and Gmail ignore most CSS). The `<style>` block only adds dark mode and the small-screen padding.
- Order: brand, status pill, headline, one-sentence intro, details (work order, status, reference), one CTA, footer saying why you got it.
- Hidden preheader: the intro, shown in the inbox list.
- The status pill says the state in words; colour only reinforces it.
- CTA: a table cell with the link inside, 44 px tall, so the whole button is clickable everywhere.
- Every email has a plain-text part with the same content.

## Checked

- Contrast: every text pair above is at least 4.5:1 (the muted labels started at 4.55:1 and were raised to 7.2:1).
- Rendered in Mailpit in light and dark mode and at 375 px (no horizontal scroll); the plain-text part reads on its own.
- Fixed after the first render: the details block's corners were square in dark mode (the cells' background painted over the table's radius), and the brand mark stayed dark blue next to the lightened wordmark.
