# Screens (overrides: none; layout specs)

Three screens for a bank's dispatcher (roles DISPATCHER, CLIENT_ADMIN), and one for the company admin (CLIENT_ADMIN). Technicians use the phone app (not built here).

## 1. Work orders `/work-orders` (home)

```
[Dispatch]  Meghna Bank PLC                     ● Live · updated 3 s ago   [Rahim D. ▾]
------------------------------------------------------------------------------------
Work orders                                                   [+ New work order]
[All] [Needs approval] [Waiting for technician] [In progress] [Drafts] [Done]

TITLE / SITE                     STATUS                  WINDOW              BUDGET
CCTV camera offline              ● Needs your approval   1 Dec 10:00–14:00   ৳3,000.00
Gulshan Avenue, Dhaka
...
                                [Load more]
```

- Rows come from `GET /api/work-orders?status=&limit=50&after=` (newest first, keyset pages: "Load more").
- Tabs map to one status each where the API filters by one: Needs approval = SUBMITTED, Waiting for technician = ROUTED, In progress = IN_PROGRESS, Drafts = DRAFT; "Done" = APPROVED; All = no filter.
- **Live:** an SSE stream (`/api/live/work-orders`) updates a row's status in place and flashes it; a work order not on screen is fetched and shown on top (All tab, or the tab its new status belongs to). On reconnect, the list reloads once (missed events are covered by the reload, ADR 0007).
- Empty: "No work orders here yet." + New work order button.
- Phone: rows become cards (title, pill, window), tabs scroll horizontally.

## 2. Work order `/work-orders/:id`

```
← Work orders
CCTV camera offline                         ● Needs your approval
Gulshan Avenue, Dhaka · ref 01a1e8…  [copy]
                                         [Reject…]  [Approve]          (actions depend on status)

[Progress]  Draft ─ Finding ─ Offered ─ Assigned ─ In progress ─ ● Needs approval ─ Approved

┌ Details ───────────────────────┐  ┌ Technician ───────────────────────┐
│ Window   1 Dec, 10:00–14:00    │  │ Rahim Uddin        ★ 4.80          │
│ Budget   ৳3,000.00             │  │ cctv · networking · printer        │
│ Skills   cctv                  │  │ Works within 30 km                 │
│ Description …                  │  └────────────────────────────────────┘
└────────────────────────────────┘
┌ Checklist ─────────────────────┐  ┌ Photos ───────────────────────────┐
│ ☐ Photo of the working camera  │  │ [thumb] camera3.jpg · 412 KB      │
└────────────────────────────────┘  └────────────────────────────────────┘
```

- `GET /api/work-orders/{id}` (keeps the ETag for edits), `GET .../attachments` (presigned download URLs, shown as thumbnails), `GET /api/technicians/{id}` when assigned.
- Actions by status: DRAFT → Publish, Cancel; PUBLISHED / ROUTED → Cancel; SUBMITTED → Approve, Reject (dialog with required reason); others → none. Each action: `POST .../{action}`, then the page refreshes; 409 shows the API's detail inline ("Only a draft can be published…").
- Live: the same stream; an event for this work order refetches it.
- Rejected work shows the reason in an amber note under the title.

### AI check (step 3.5)

```
┌ AI CHECK ─────────────────────────────────────── 92% sure ┐
│ Needs your review: item 2 is unclear from the photos.     │
│ ✓ Met      Photo of the camera mounted                    │
│            Dome camera on the corner bracket.             │
│ ? Unclear  Photo of the live feed                         │
│            No photo shows a monitor or screen.            │
│ gemini-3.5-flash-lite · prompt v1 · The AI recommends; you decide.
└───────────────────────────────────────────────────────────┘
```

- From `GET /api/work-orders/{id}/audit`, for SUBMITTED work and after a decision (404 = no audit: no panel). Placed above Checklist, full width.
- **Summary line:** "Approved automatically" (green) or "Needs your review" (orange, same as the status pill), then the policy's reason. After a decision: "Approved by a reviewer", "Sent back: <reason>", "Decided on the work order".
- **Items:** an icon *and* a word per verdict (✓ Met green, ✗ Not met red, ? Unclear amber), never color alone; the evidence below in muted text.
- **While the check runs** (SUBMITTED, no audit yet): "AI check running…" with a spinner; the page polls every 3 s for up to 2 minutes.
- **Approve and Reject** in the header go through the review endpoints while an audit is open, so the decision is recorded against what the AI said; otherwise through the work order endpoints as before.

## 3. New work order `/work-orders/new`

Fields: Title, Description, Site address, Site location (latitude, longitude; a hint shows Dhaka's range), Required skills (comma-separated chips, lowercase-with-dashes), Time window (start, end; datetime-local in the browser's time zone), Budget (BDT, two decimals → minor units), Checklist (one item per line, at least one).

- Submit: `POST /api/work-orders` → on 201, "Publish now" or "Save as draft" (two buttons: the second only creates; the first creates then publishes).
- Validation: client-side for required fields and number ranges; server Problem Details field errors (`errors[]`) shown under each field; other problems in an alert at the top.

### Describe the job (step 3.3)

```
┌ Describe the job (optional) ──────────────────────────────────┐
│ [Gulshan office printer shows E05, need someone tomorrow…    ]│
│ Paste the branch's message; AI fills the form for you to check│
│                                            [✦ Fill the form]  │
│ Filled 6 fields from the message. Still to fill: site location│  (role=status)
└───────────────────────────────────────────────────────────────┘
```

- **Top of the form, optional.** A panel with a textarea and a secondary button. The form below works the same with or without it.
- **Fills empty fields only.** Anything the dispatcher already typed is kept. The message itself goes into Description.
- **What was filled is visible.** Each AI-filled field gets the `primary-soft` background and " (filled by AI)" for screen readers, until the dispatcher edits it. Nothing is submitted for them: Save and Publish stay their decision.
- **Status line** (one `role="status"` message, focus doesn't move):
  - "Filled N fields from the message. Still to fill: site location[, …]." Latitude and longitude are always left to the person: a language model shouldn't guess coordinates.
  - "Sounds urgent: the time window starts within the hour." when urgency is URGENT.
  - Unavailable: "Couldn't read the message right now. Fill the form yourself; your text is in Description." Muted, not red.
- **Loading:** the button shows the spinner with "Reading…", keeping its width. Typical 1-8 s; the form stays usable.

### Skill suggestions (step 3.2)

```
Required skills                                   [✦ Suggest from description]
[ printer, networking                              ]
Suggested by AI: [+ printer] [+ networking]       (role=status)
```

- **Trigger: a button, not automatic.** Each suggestion is a metered model call under a daily company budget; suggesting on every keystroke would spend it on half-typed text. Secondary button, sparkles icon plus words (never icon-only), next to the field label; enabled once title and description have text.
- **Loading:** the button keeps its width and shows the spinner with "Suggesting…"; the form stays usable (typing skills by hand is always possible). Typical wait 1-8 s, worst 25 s, so no skeleton: the rest of the form isn't waiting.
- **Result:** "Suggested by AI:" (the label says it's AI, ux AI-disclaimer) and one outline pill button per skill, `+ code`; pressing it adds the code to the field (no duplicates) and turns the pill into `✓ code`, disabled. Pills are 44 px high on touch. Nothing is added without a press: the dispatcher decides.
- **Empty:** "No skill on our list fits. Type the skills yourself." **Unavailable** (`available: false`, or the request failed): "Suggestions aren't available right now. Type the skills yourself." Muted text, never red: nothing is wrong with the form.
- **Accessibility:** the result line is one `role="status"` `aria-atomic` message ("2 suggested skills: printer, networking"); focus stays where it is; each pill's accessible name is "Add skill printer".
- Changing the title or description clears old suggestions, so they never describe different text.

## 4. AI usage `/ai-usage` (company admins, step 3.6)

```
AI usage                                                    [7 days][30 days][90 days]
What your company's AI features used, at the provider's list price. ...
┌ TODAY ──────────┐ ┌ COST, 30 DAYS ─┐ ┌ CALLS, 30 DAYS ┐ ┌ NO AI ANSWER ───────────┐
│ 23 of 100 calls │ │ $0.0017        │ │ 41             │ │ 26                      │
│ ▓▓▓▓░░░░░░░░░░  │ │ At list price; │ │ 13,572 tokens  │ │ 23 failed, 3 not sent;  │
│ 77 left today   │ │ the free tier  │ │                │ │ the forms carried on    │
└─────────────────┘ └────────────────┘ └────────────────┘ └─────────────────────────┘
┌ CALLS PER DAY (UTC) ─────────────────────────────────────────────────────────────┐
│ Busiest day: 17 Nov, 39 calls          (hover or focus a bar: date, calls, cost) │
│                                                                    █             │
│ 20 Oct                         3 Nov                         18 Nov █ ▁          │
│ ▸ Show as a table                                                                │
└──────────────────────────────────────────────────────────────────────────────────┘
┌ BY FEATURE ───────────────────────────────────────────────────────────────────────┐
│ Feature                 Calls  Failed  Not sent  Tokens in  Tokens out      Cost │
│ Describe the job           11       4         1      5,961         914  $0.00096 │
└───────────────────────────────────────────────────────────────────────────────────┘
Days are UTC days, the same days the daily limit counts (it resets at 6:00 in Dhaka). ...
```

- From `GET /api/ai/usage?days=7|30|90`; the period is in the address (`?days=7`), 30 by default. The app bar shows "Work orders" and "AI usage" links to company admins only; on phones the brand keeps its mark and drops the word so the bar fits.
- **Today** is a `role="meter"` bar against the daily limit with words below: "77 left today", amber from 80% ("close to the limit"), red at the limit ("Limit reached: AI is off until tomorrow, forms work as usual"). Never color alone.
- **Money:** amounts under a cent keep two significant digits (`$0.00096`), because `$0.00` would read as free; a cent and up has two decimals. All numbers in Fira Code with tabular figures, right-aligned in tables.
- **Chart:** one bar per UTC day, a single blue (one series, so no legend); zero days draw nothing. Each bar is a button whose accessible name has the numbers ("17 Nov: 39 calls, $0.0013"); hover or focus writes them in the line above (`aria-live="polite"`). "Show as a table" gives every day as text, so nothing is only in the picture.
- **By feature:** features in the app's words ("Describe the job", "AI check of finished work"). Semantic table on desktop; on phones one card per feature with a 3-column `dl`.
- **Empty:** "No AI calls in the last 30 days." and what starts them. **Not an admin:** "Only your company's admins can see AI usage." and no period switch.
- Skeletons in the shape of the tiles, chart and table while loading.

