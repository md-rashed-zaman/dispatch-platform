import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Check, Copy, ImageIcon, MapPin, Star } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { api, ApiError, post, type Attachment, type Status, type TechnicianProfile, type WorkOrder } from "../api";
import { Button, ConfirmDialog, ErrorNote, Panel, Skeleton, StatusPill } from "../components/ui";
import { money, shortId, STATUS, timeWindow } from "../format";
import { useRecentChanges } from "../recent";

/** The path a work order normally takes, for the progress line. */
const PROGRESS: Status[] = ["DRAFT", "PUBLISHED", "ROUTED", "ASSIGNED", "IN_PROGRESS", "SUBMITTED", "APPROVED"];

export function WorkOrderPage() {
  const { id = "" } = useParams();
  const queryClient = useQueryClient();
  const recent = useRecentChanges();
  const workOrder = useQuery({ queryKey: ["work-orders", "detail", id], queryFn: () => api<WorkOrder>(`/api/work-orders/${id}`) });
  const attachments = useQuery({
    queryKey: ["work-orders", "attachments", id],
    queryFn: () => api<Attachment[]>(`/api/work-orders/${id}/attachments`),
    enabled: workOrder.isSuccess,
  });
  const technicianId = workOrder.data?.technicianId;
  const technician = useQuery({
    queryKey: ["technicians", technicianId],
    queryFn: () => api<TechnicianProfile>(`/api/technicians/${technicianId}`),
    enabled: !!technicianId,
  });

  const [confirm, setConfirm] = useState<"cancel" | "reject" | null>(null);
  const [reason, setReason] = useState("");
  const action = useMutation({
    mutationFn: ({ name, body }: { name: string; body?: unknown }) => post(`/api/work-orders/${id}/${name}`, body),
    onSuccess: () => {
      setConfirm(null);
      setReason("");
      void queryClient.invalidateQueries({ queryKey: ["work-orders"] });
    },
  });

  if (workOrder.isPending) {
    return <DetailSkeleton />;
  }
  if (workOrder.isError) {
    const notFound = workOrder.error instanceof ApiError && workOrder.error.problem.status === 404;
    return (
      <div className="py-16 text-center">
        <p className="font-medium">{notFound ? "This work order doesn't exist, or isn't yours." : "Couldn't load this work order."}</p>
        <Link to="/work-orders" className="mt-3 inline-block text-link underline">Back to work orders</Link>
      </div>
    );
  }

  const w = workOrder.data;
  const actionError = action.error instanceof ApiError ? action.error.message : action.error ? "Something went wrong." : null;

  return (
    <div className={recent.has(w.id) ? "flash rounded-[10px]" : ""} key={recent.get(w.id) ?? 0}>
      <Link to="/work-orders" className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft aria-hidden className="size-4" /> Work orders
      </Link>

      <div className="mt-3 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold tracking-tight">{w.title}</h1>
            <StatusPill status={w.status} />
          </div>
          <p className="mt-1 flex flex-wrap items-center gap-x-2 text-sm text-muted-foreground">
            <span className="inline-flex items-center gap-1"><MapPin aria-hidden className="size-3.5" />{w.siteAddress}</span>
            <span aria-hidden>·</span>
            <CopyId id={w.id} />
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {(w.status === "DRAFT" || w.status === "PUBLISHED" || w.status === "ROUTED") && (
            <Button variant="secondary" onClick={() => setConfirm("cancel")}>Cancel work order</Button>
          )}
          {w.status === "DRAFT" && (
            <Button loading={action.isPending} onClick={() => action.mutate({ name: "publish" })}>Publish</Button>
          )}
          {w.status === "SUBMITTED" && (
            <>
              <Button variant="secondary" onClick={() => setConfirm("reject")}>Send back</Button>
              <Button loading={action.isPending} onClick={() => action.mutate({ name: "approve" })}>
                <Check aria-hidden className="size-4" /> Approve
              </Button>
            </>
          )}
        </div>
      </div>

      {w.rejectionReason && w.status !== "APPROVED" && (
        <p className="mt-4 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200">
          Sent back: {w.rejectionReason}
        </p>
      )}
      {actionError && !confirm && <div className="mt-4"><ErrorNote>{actionError}</ErrorNote></div>}

      <ProgressLine status={w.status} />

      <div className="mt-6 grid gap-4 lg:grid-cols-2">
        <Panel title="Details">
          <dl className="grid grid-cols-[7rem_1fr] gap-x-4 gap-y-2.5 text-sm">
            <dt className="text-muted-foreground">Time window</dt>
            <dd className="data text-[13px]">{timeWindow(w.timeWindow)}</dd>
            <dt className="text-muted-foreground">Budget</dt>
            <dd className="data text-[13px]">{money(w.budget)}</dd>
            <dt className="text-muted-foreground">Skills</dt>
            <dd className="flex flex-wrap gap-1.5">{w.requiredSkills.map((s) => <Chip key={s}>{s}</Chip>)}</dd>
            <dt className="text-muted-foreground">Description</dt>
            <dd className="whitespace-pre-line">{w.description}</dd>
          </dl>
        </Panel>

        <Panel title="Technician">
          {!technicianId && <p className="text-sm text-muted-foreground">{waitingText(w.status)}</p>}
          {technician.isPending && technicianId && <Skeleton className="h-12" />}
          {technician.data && (
            <div>
              <div className="flex items-center justify-between gap-3">
                <span className="font-medium">{technician.data.fullName}</span>
                {technician.data.rating !== null && (
                  <span className="data inline-flex items-center gap-1 text-[13px]">
                    <Star aria-hidden className="size-3.5 fill-amber-400 text-amber-400" />
                    {technician.data.rating.toFixed(2)}
                  </span>
                )}
              </div>
              <div className="mt-2 flex flex-wrap gap-1.5">{technician.data.skills.map((s) => <Chip key={s}>{s}</Chip>)}</div>
              <p className="mt-2 text-sm text-muted-foreground">Works within {technician.data.serviceRadiusKm} km of home</p>
            </div>
          )}
        </Panel>

        <Panel title="Checklist">
          <ol className="space-y-2 text-sm">
            {w.checklist.map((item) => (
              <li key={item.position} className="flex gap-2.5">
                <span className="data mt-px text-[13px] text-muted-foreground">{item.position + 1}.</span>
                {item.description}
              </li>
            ))}
          </ol>
        </Panel>

        <Panel title={`Photos${attachments.data?.length ? ` (${attachments.data.length})` : ""}`}>
          {attachments.isPending && <Skeleton className="h-24" />}
          {attachments.data?.length === 0 && (
            <p className="text-sm text-muted-foreground">The technician uploads photos while doing the work.</p>
          )}
          <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {attachments.data?.map((a) => (
              <li key={a.id}>
                <a href={a.downloadUrl} target="_blank" rel="noreferrer" className="group block">
                  {a.contentType.startsWith("image/") ? (
                    <img src={a.downloadUrl} alt={a.fileName} loading="lazy" className="aspect-[4/3] w-full rounded-md border border-border object-cover" />
                  ) : (
                    <span className="grid aspect-[4/3] place-items-center rounded-md border border-border bg-muted">
                      <ImageIcon aria-hidden className="size-6 text-muted-foreground" />
                    </span>
                  )}
                  <span className="mt-1 block truncate text-xs group-hover:underline">{a.fileName}</span>
                  <span className="data block text-[11px] text-muted-foreground">{Math.round(a.size / 1024)} KB</span>
                </a>
              </li>
            ))}
          </ul>
        </Panel>
      </div>

      <ConfirmDialog
        open={confirm === "cancel"}
        onOpenChange={(open) => !open && setConfirm(null)}
        title="Cancel this work order?"
        description="Technicians stop seeing it, and it can't be published again."
        confirm={
          <Button variant="destructive" loading={action.isPending} onClick={() => action.mutate({ name: "cancel" })}>
            Cancel work order
          </Button>
        }>
        {actionError && <div className="mt-3"><ErrorNote>{actionError}</ErrorNote></div>}
      </ConfirmDialog>

      <ConfirmDialog
        open={confirm === "reject"}
        onOpenChange={(open) => !open && setConfirm(null)}
        title="Send the work back?"
        description="The technician sees your reason and does the work again."
        confirm={
          <Button
            variant="destructive"
            loading={action.isPending}
            disabled={reason.trim().length === 0}
            onClick={() => action.mutate({ name: "reject", body: { reason: reason.trim() } })}>
            Send back
          </Button>
        }>
        <label htmlFor="reason" className="mt-4 block text-sm font-medium">Reason</label>
        <textarea
          id="reason"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          placeholder="What's missing or wrong, so the technician can fix it."
          className="mt-1.5 w-full rounded-md border border-border bg-card px-3 py-2 text-base"
        />
        {actionError && <div className="mt-3"><ErrorNote>{actionError}</ErrorNote></div>}
      </ConfirmDialog>
    </div>
  );
}

function ProgressLine({ status }: { status: Status }) {
  if (status === "CANCELLED") {
    return <p className="mt-6 text-sm text-muted-foreground">Cancelled: technicians no longer see this work order.</p>;
  }
  const current = status === "PAID" ? PROGRESS.length : PROGRESS.indexOf(status);
  const step = Math.min(current, PROGRESS.length - 1);
  return (
    <>
      {/* Phones: the bar and where it stands, in words; labels for every step don't fit. */}
      <div className="mt-6 sm:hidden">
        <div aria-hidden className="flex gap-0.5">
          {PROGRESS.map((s, i) => (
            <span key={s} className={`h-1 flex-1 rounded-full ${i <= current ? "bg-primary" : "bg-border"}`} />
          ))}
        </div>
        <p className="mt-2 text-xs text-muted-foreground">
          Step {step + 1} of {PROGRESS.length}: <span className="font-medium text-foreground">{STATUS[PROGRESS[step]].label}</span>
        </p>
      </div>
      <ProgressSteps current={current} />
    </>
  );
}

function ProgressSteps({ current }: { current: number }) {
  return (
    <ol aria-label="Progress" className="mt-6 flex max-sm:hidden">
      {PROGRESS.map((step, i) => {
        const done = i < current;
        const now = i === current;
        return (
          <li key={step} className="flex min-w-24 flex-1 flex-col gap-1.5" aria-current={now ? "step" : undefined}>
            <span className={`h-1 rounded-full ${done || now ? "bg-primary" : "bg-border"} ${i > 0 ? "ml-0.5" : ""}`} />
            <span className={`text-xs ${now ? "font-medium text-foreground" : done ? "text-muted-foreground" : "text-muted-foreground/70"}`}>
              {STATUS[step].label}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

function waitingText(status: Status) {
  switch (status) {
    case "DRAFT":
      return "Publish it and Dispatch offers it to the nearest technicians with the skills.";
    case "PUBLISHED":
      return "Looking for technicians nearby.";
    case "ROUTED":
      return "Offered to technicians nearby; the first to accept gets it.";
    default:
      return "No technician.";
  }
}

function Chip({ children }: { children: string }) {
  return <span className="rounded border border-border bg-muted px-1.5 py-0.5 text-xs">{children}</span>;
}

function CopyId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="data inline-flex cursor-pointer items-center gap-1 text-[13px] hover:text-foreground"
      title="Copy the full reference"
      onClick={() => {
        void navigator.clipboard.writeText(id);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      }}>
      ref {shortId(id)}
      {copied ? <Check aria-hidden className="size-3.5 text-ok" /> : <Copy aria-hidden className="size-3.5" />}
      <span className="sr-only">{copied ? "Copied" : "Copy reference"}</span>
    </button>
  );
}

function DetailSkeleton() {
  return (
    <div aria-label="Loading work order">
      <Skeleton className="h-4 w-28" />
      <Skeleton className="mt-4 h-8 w-80" />
      <Skeleton className="mt-2 h-4 w-60" />
      <div className="mt-8 grid gap-4 lg:grid-cols-2">
        {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-40" />)}
      </div>
    </div>
  );
}
