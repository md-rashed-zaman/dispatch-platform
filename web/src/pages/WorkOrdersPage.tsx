import { useInfiniteQuery } from "@tanstack/react-query";
import { ClipboardList, Plus } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api, type Page, type Status, type WorkOrderSummary } from "../api";
import { Button, ErrorNote, Skeleton, StatusPill } from "../components/ui";
import { money, timeWindow } from "../format";
import { useRecentChanges } from "../recent";

/** Tabs filter by one status each (the API filters by one). */
const TABS: { key: string; label: string; status?: Status }[] = [
  { key: "all", label: "All" },
  { key: "approval", label: "Needs approval", status: "SUBMITTED" },
  { key: "waiting", label: "Waiting for technician", status: "ROUTED" },
  { key: "progress", label: "In progress", status: "IN_PROGRESS" },
  { key: "drafts", label: "Drafts", status: "DRAFT" },
  { key: "done", label: "Done", status: "APPROVED" },
];

export function WorkOrdersPage() {
  const [params, setParams] = useSearchParams();
  const tab = TABS.find((t) => t.key === params.get("tab")) ?? TABS[0];
  const recent = useRecentChanges();
  const navigate = useNavigate();

  const list = useInfiniteQuery({
    queryKey: ["work-orders", "list", tab.status ?? "all"],
    queryFn: ({ pageParam }) => {
      const query = new URLSearchParams({ limit: "50" });
      if (tab.status) query.set("status", tab.status);
      if (pageParam) query.set("after", pageParam);
      return api<Page<WorkOrderSummary>>(`/api/work-orders?${query}`);
    },
    initialPageParam: "",
    getNextPageParam: (last) => last.next ?? undefined,
  });
  const rows = list.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Work orders</h1>
        <Button onClick={() => navigate("/work-orders/new")}>
          <Plus aria-hidden className="size-4" />
          New work order
        </Button>
      </div>

      <nav aria-label="Filter by status" className="-mx-4 mt-5 overflow-x-auto overflow-y-hidden border-b border-border px-4 [scrollbar-width:none] sm:mx-0 sm:px-0">
        <div className="flex w-max gap-1">
          {TABS.map((t) => {
            const active = t.key === tab.key;
            return (
              <button
                key={t.key}
                onClick={() => setParams(t.key === "all" ? {} : { tab: t.key })}
                aria-current={active ? "page" : undefined}
                className={`-mb-px cursor-pointer whitespace-nowrap border-b-2 px-3 py-2 text-sm transition-colors ${
                  active
                    ? "border-primary font-medium text-foreground"
                    : "border-transparent text-muted-foreground hover:text-foreground"
                }`}>
                {t.label}
              </button>
            );
          })}
        </div>
      </nav>

      <div className="mt-4">
        {list.isError && <ErrorNote>Couldn't load work orders: {list.error.message}</ErrorNote>}
        {list.isPending && <TableSkeleton />}
        {list.isSuccess && rows.length === 0 && (
          <div className="rounded-[10px] border border-dashed border-border bg-card px-6 py-14 text-center">
            <ClipboardList aria-hidden className="mx-auto size-8 text-muted-foreground" />
            <p className="mt-3 font-medium">No work orders here yet.</p>
            <p className="mt-1 text-sm text-muted-foreground">
              {tab.status ? "Nothing in this state right now." : "Create one and Dispatch finds a technician nearby."}
            </p>
            {!tab.status && (
              <Button className="mt-4" onClick={() => navigate("/work-orders/new")}>
                New work order
              </Button>
            )}
          </div>
        )}
        {rows.length > 0 && (
          <>
            {/* Desktop: a table. Phones: one card per work order. */}
            <div className="overflow-hidden rounded-[10px] border border-border bg-card max-md:hidden">
              {/* Fixed columns: a live status change mustn't shift the layout under the reader. */}
              <table className="w-full table-fixed text-left text-sm">
                <colgroup>
                  <col />
                  <col className="w-48" />
                  <col className="w-44" />
                  <col className="w-28" />
                </colgroup>
                <thead className="border-b border-border text-xs uppercase tracking-[0.04em] text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-4 py-2.5 font-medium">Work order</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Status</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Time window</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-medium">Budget</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr
                      key={`${row.id}-${recent.get(row.id) ?? 0}`}
                      onClick={() => navigate(`/work-orders/${row.id}`)}
                      className={`cursor-pointer border-b border-border transition-colors last:border-0 hover:bg-muted ${recent.has(row.id) ? "flash" : ""}`}>
                      <td className="truncate px-4 py-2.5">
                        <Link to={`/work-orders/${row.id}`} className="font-medium hover:underline" onClick={(e) => e.stopPropagation()}>
                          {row.title}
                        </Link>
                        <div className="truncate text-muted-foreground">{row.siteAddress}</div>
                      </td>
                      <td className="px-4 py-2.5">
                        <StatusPill status={row.status} />
                      </td>
                      <td className="data px-4 py-2.5 text-[13px]">{timeWindow(row.timeWindow)}</td>
                      <td className="data px-4 py-2.5 text-right text-[13px]">{money(row.budget)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="space-y-2 md:hidden">
              {rows.map((row) => (
                <li key={`${row.id}-${recent.get(row.id) ?? 0}`}>
                  <Link
                    to={`/work-orders/${row.id}`}
                    className={`block rounded-[10px] border border-border bg-card p-4 ${recent.has(row.id) ? "flash" : ""}`}>
                    <div className="flex items-start justify-between gap-3">
                      <span className="font-medium">{row.title}</span>
                      <StatusPill status={row.status} />
                    </div>
                    <div className="mt-1 text-sm text-muted-foreground">{row.siteAddress}</div>
                    <div className="data mt-2 flex justify-between text-[13px]">
                      <span>{timeWindow(row.timeWindow)}</span>
                      <span>{money(row.budget)}</span>
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          </>
        )}
        {list.hasNextPage && (
          <div className="mt-4 text-center">
            <Button variant="secondary" loading={list.isFetchingNextPage} onClick={() => list.fetchNextPage()}>
              Load more
            </Button>
          </div>
        )}
      </div>
    </>
  );
}

function TableSkeleton() {
  return (
    <div className="space-y-px overflow-hidden rounded-[10px] border border-border bg-card p-4" aria-label="Loading work orders">
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="flex items-center gap-6 py-2.5">
          <Skeleton className="h-4 flex-1" />
          <Skeleton className="h-5 w-32 rounded-full" />
          <Skeleton className="h-4 w-36" />
          <Skeleton className="h-4 w-20" />
        </div>
      ))}
    </div>
  );
}
