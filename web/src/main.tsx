import "@fontsource/fira-code/400.css";
import "@fontsource/fira-code/500.css";
import "@fontsource/fira-sans/400.css";
import "@fontsource/fira-sans/500.css";
import "@fontsource/fira-sans/600.css";
import "./index.css";

import { QueryClient, QueryClientProvider, useQuery, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { StrictMode, useCallback, useState } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, Outlet, RouterProvider, ScrollRestoration } from "react-router";
import { api, type Me, type Page, type StatusChange, type WorkOrderSummary } from "./api";
import { AuthGate } from "./auth";
import { AppShell } from "./components/AppShell";
import { useLiveUpdates } from "./live";
import { WorkOrdersPage } from "./pages/WorkOrdersPage";
import { RecentChanges } from "./recent";

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false, staleTime: 10_000 } },
});

/** Signed-in layout: live updates for the whole app, applied to every cached list and page. */
function Layout() {
  const client = useQueryClient();
  const [recent, setRecent] = useState(() => new Map<string, number>());
  const me = useQuery({ queryKey: ["me"], queryFn: () => api<Me>("/api/me") });

  const onChange = useCallback(
    (change: StatusChange) => {
      // Update the row in place at once, then refetch quietly so tabs and pages are exact.
      client.setQueriesData<InfiniteData<Page<WorkOrderSummary>>>({ queryKey: ["work-orders", "list"] }, (data) =>
        data && {
          ...data,
          pages: data.pages.map((page) => ({
            ...page,
            items: page.items.map((item) => (item.id === change.workOrderId ? { ...item, status: change.to } : item)),
          })),
        });
      void client.invalidateQueries({ queryKey: ["work-orders"] });
      const at = Date.now();
      setRecent((current) => new Map(current).set(change.workOrderId, at));
      setTimeout(
        () => setRecent((current) => {
          if (current.get(change.workOrderId) !== at) return current;
          const next = new Map(current);
          next.delete(change.workOrderId);
          return next;
        }),
        1500,
      );
    },
    [client],
  );
  const live = useLiveUpdates(onChange);

  if (me.data && !me.data.roles.some((r) => r === "DISPATCHER" || r === "CLIENT_ADMIN")) {
    return (
      <div className="grid min-h-screen place-items-center p-6 text-center">
        <div>
          <p className="text-lg font-semibold">This app is for a company's dispatchers</p>
          <p className="mt-1 text-sm text-muted-foreground">Technicians use the Dispatch phone app.</p>
        </div>
      </div>
    );
  }
  return (
    <RecentChanges value={recent}>
      <AppShell live={live}>
        <Outlet />
        <ScrollRestoration />
      </AppShell>
    </RecentChanges>
  );
}

/**
 * Created after sign-in, not at module load: the sign-in callback restores the address the user asked for
 * (with its query) first, and the router must start from that, not from /auth/callback.
 */
function App() {
  const [router] = useState(() =>
    createBrowserRouter([
      {
        element: <Layout />,
        children: [
          { path: "/", element: <Navigate to="/work-orders" replace /> },
          { path: "/work-orders", element: <WorkOrdersPage /> },
          // Loaded when first opened: the list is what everyone sees first.
          { path: "/work-orders/new", lazy: () => import("./pages/NewWorkOrderPage").then((m) => ({ Component: m.NewWorkOrderPage })) },
          { path: "/work-orders/:id", lazy: () => import("./pages/WorkOrderPage").then((m) => ({ Component: m.WorkOrderPage })) },
          { path: "*", element: <Navigate to="/work-orders" replace /> },
        ],
      },
    ]),
  );
  return <RouterProvider router={router} />;
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AuthGate>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </AuthGate>
  </StrictMode>,
);
