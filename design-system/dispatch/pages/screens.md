# Screens (overrides: none; layout specs)

Three screens for a bank's dispatcher (roles DISPATCHER, CLIENT_ADMIN). Technicians use the phone app (not built here).

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

## 3. New work order `/work-orders/new`

Fields: Title, Description, Site address, Site location (latitude, longitude; a hint shows Dhaka's range), Required skills (comma-separated chips, lowercase-with-dashes), Time window (start, end; datetime-local in the browser's time zone), Budget (BDT, two decimals → minor units), Checklist (one item per line, at least one).

- Submit: `POST /api/work-orders` → on 201, "Publish now" or "Save as draft" (two buttons: the second only creates; the first creates then publishes).
- Validation: client-side for required fields and number ranges; server Problem Details field errors (`errors[]`) shown under each field; other problems in an alert at the top.
