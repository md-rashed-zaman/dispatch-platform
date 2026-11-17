# Dispatch web app

The dispatcher's screen: a bank's (or any company's) work orders, updating live, with publishing, approving and sending work back. React, TypeScript, Vite, Tailwind. Design: [`design-system/dispatch`](../design-system/dispatch/MASTER.md).

## Run it

Needs the stack and the API running (see the main README), Node 24.

```bash
npm install
npm run dev
```

Open http://localhost:5173 and sign in as `dispatcher.meghna` / `password` (local test user).

`npm run build` type-checks and builds to `dist/`.

## How it's put together

| Concern | Choice | Why |
|---|---|---|
| Sign-in | `oidc-client-ts`: authorization code + PKCE, Keycloak public client `dispatch-web` | A browser can't keep a client secret; PKCE proves the code belongs to the browser that asked for it |
| Tokens | In memory only, renewed with the refresh token | localStorage and sessionStorage are readable by any injected script. A reload goes back through Keycloak, instant while its session lasts |
| Server data | TanStack Query | Caching, refetching and invalidation in one place; no hand-written loading state |
| Live updates | `fetch` + `@microsoft/fetch-event-source` on `/api/live/work-orders` | `EventSource` can't send a bearer token. Every (re)connect gets a fresh token; after a reconnect the lists reload once, which covers anything missed |
| API access | Vite proxies `/api` to the API | One origin, so the API needs no CORS. In production, a reverse proxy does the same |
| Routing | React Router, created after sign-in | So a deep link (an email's "Review and approve") survives the sign-in round trip |
| Fonts | Fira Sans and Fira Code, self-hosted | Text and data (ids, money, times) in tabular figures; no third-party font requests |

Pages: `src/pages/WorkOrdersPage.tsx` (list), `WorkOrderPage.tsx` (detail and actions), `NewWorkOrderPage.tsx` (form). Shared pieces in `src/components`.
