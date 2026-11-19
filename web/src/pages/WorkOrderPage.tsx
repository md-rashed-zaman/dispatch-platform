import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Check, CircleCheck, CircleHelp, Copy, CreditCard, ImageIcon, LoaderCircle, MapPin, Star, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import { api, ApiError, post, type Attachment, type Audit, type Payment, type Status, type TechnicianProfile, type WorkOrder } from "../api";
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

  // The AI check arrives a few seconds after submission (step 3.5): poll for it, for at most two minutes.
  const pollingSince = useRef(Date.now());
  const audit = useQuery({
    queryKey: ["work-orders", "audit", id],
    queryFn: () =>
      api<Audit>(`/api/work-orders/${id}/audit`).catch((e) => {
        if (e instanceof ApiError && e.problem.status === 404) return null; // not audited (yet)
        throw e;
      }),
    enabled: workOrder.isSuccess,
    refetchInterval: (q) =>
      workOrder.data?.status === "SUBMITTED" && !q.state.data && Date.now() - pollingSince.current < 120_000 ? 3000 : false,
  });
  const openAudit = audit.data?.state === "NEEDS_REVIEW" ? audit.data : null;

  const [confirm, setConfirm] = useState<"cancel" | "reject" | null>(null);
  const [reason, setReason] = useState("");
  const action = useMutation({
    // While an audit is open, decisions go through the review queue, so they're recorded against what the AI said.
    mutationFn: ({ name, body }: { name: string; body?: unknown }) =>
      openAudit && (name === "approve" || name === "reject")
        ? post(`/api/reviews/${openAudit.id}/${name}`, body)
        : post(`/api/work-orders/${id}/${name}`, body),
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

        <Funding workOrder={w} />

        {(audit.data || (w.status === "SUBMITTED" && audit.isSuccess)) && (
          <div className="lg:col-span-2">
            <AiCheck audit={audit.data ?? null} checklist={w.checklist} notes={w.completionNotes} />
          </div>
        )}

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

const FUNDABLE: Status[] = ["ASSIGNED", "IN_PROGRESS"];

/**
 * Paying for the job (step 4.2, payment-flow.md). The client pays on the provider's page; coming back, the page
 * checks with Dispatch (which asks the provider) until the payment is settled. The browser never decides.
 */
function Funding({ workOrder }: { workOrder: WorkOrder }) {
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  // Where the provider sent the client back: "returned" means check; "declined" or "cancelled" is only what the
  // browser was told, used for wording, never for the payment's state.
  const said = params.get("payment");
  const returned = said === "returned";
  const pollingSince = useRef(Date.now());
  const payment = useQuery({
    queryKey: ["work-orders", "payment", workOrder.id],
    queryFn: () =>
      api<Payment>(`/api/work-orders/${workOrder.id}/payment`).catch((e) => {
        if (e instanceof ApiError && e.problem.status === 404) return null; // never paid
        throw e;
      }),
    refetchInterval: (q) => {
      const open = q.state.data?.status === "PENDING" || q.state.data?.status === "INITIATED";
      return returned && open && Date.now() - pollingSince.current < 60_000 ? 2000 : false;
    },
  });
  const p = payment.data ?? null;
  const funded = workOrder.fundedAt !== null || p?.status === "CAPTURED";

  useEffect(() => {
    if (p && p.status !== "PENDING" && p.status !== "INITIATED") {
      if (said) setParams({}, { replace: true }); // settled: a reload shouldn't look like a fresh return
      if (p.status === "CAPTURED" && !workOrder.fundedAt) void queryClient.invalidateQueries({ queryKey: ["work-orders"] });
    }
  }, [p, said, setParams, workOrder.fundedAt, queryClient]);

  // One key per attempt, kept if the request may have reached the server (network error), so a retry can't pay twice.
  const key = useRef<string | null>(null);
  const fund = useMutation({
    mutationFn: () => {
      key.current ??= crypto.randomUUID();
      return api<Payment>(`/api/work-orders/${workOrder.id}/payments`, {
        method: "POST",
        headers: { "Idempotency-Key": key.current },
      });
    },
    onSuccess: (started) => {
      key.current = null;
      if (started.redirectUrl) window.location.assign(started.redirectUrl);
    },
    onError: (e) => {
      if (e instanceof ApiError) key.current = null;
    },
  });

  if (!funded && !p && !FUNDABLE.includes(workOrder.status)) return null;
  if (payment.isPending) {
    return (
      <Panel title="Payment">
        <Skeleton className="h-12" />
      </Panel>
    );
  }

  const amount = money(workOrder.budget);
  const fundError = fund.error instanceof ApiError ? fund.error.message : fund.error ? "Couldn't reach Dispatch. Try again." : null;
  const checking = returned && (p?.status === "PENDING" || p?.status === "INITIATED") && Date.now() - pollingSince.current < 60_000;
  const payButton = (label: string) => (
    <Button className="mt-3" loading={fund.isPending} onClick={() => fund.mutate()}>
      <CreditCard aria-hidden className="size-4" /> {label}
    </Button>
  );

  return (
    <Panel title="Payment">
      <div aria-live="polite">
        {funded ? (
          <p className="flex items-center gap-2 text-sm">
            <CircleCheck aria-hidden className="size-4 text-ok" />
            <span className="font-medium">Funded</span>
            <span className="data text-[13px]">{amount}</span>
            {(workOrder.fundedAt ?? p?.settledAt) && (
              <span className="text-muted-foreground">· {when(workOrder.fundedAt ?? p!.settledAt!)}</span>
            )}
          </p>
        ) : checking ? (
          <p className="flex items-center gap-2 text-sm">
            <LoaderCircle aria-hidden className="size-4 animate-spin text-muted-foreground motion-reduce:animate-none" />
            Checking your payment with the provider…
          </p>
        ) : p?.status === "PENDING" && p.reason === "Risk review" ? (
          <p className="text-sm">
            <span className="font-medium">Held for review.</span>{" "}
            <span className="text-muted-foreground">
              The payment provider flagged this payment for a check. The job is funded once it's cleared.
            </span>
          </p>
        ) : p?.status === "PENDING" && (said === "declined" || said === "cancelled") ? (
          <>
            <p className="text-sm">
              <span className="font-medium">{said === "declined" ? "The payment was declined." : "You cancelled the payment."}</span>{" "}
              <span className="text-muted-foreground">You can try again with another card or method.</span>
            </p>
            {payButton("Try again")}
          </>
        ) : p?.status === "PENDING" ? (
          <>
            <p className="text-sm">
              <span className="font-medium">Payment started, not confirmed yet.</span>{" "}
              <span className="text-muted-foreground">If you've paid, it's confirmed within 15 minutes. Otherwise continue where you left off.</span>
            </p>
            {payButton("Continue to payment")}
          </>
        ) : (
          <>
            {p && (p.status === "FAILED" || p.status === "EXPIRED") && (
              <p className="mb-1 text-sm">
                <span className="font-medium">{p.status === "EXPIRED" ? "The payment page expired." : "The payment didn't go through."}</span>{" "}
                <span className="text-muted-foreground">{p.reason}</span>
              </p>
            )}
            <p className="text-sm text-muted-foreground">
              The technician starts once you've paid <span className="data text-[13px] text-foreground">{amount}</span>, on the
              provider's page: card, bKash, Nagad or bank. The money is held until you approve the work.
            </p>
            {payButton(p ? "Try again" : "Fund this job")}
          </>
        )}
      </div>
      {fundError && <div className="mt-3"><ErrorNote>{fundError}</ErrorNote></div>}
    </Panel>
  );
}

function when(iso: string) {
  return new Date(iso).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
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

const VERDICT = {
  MET: { label: "Met", icon: Check, className: "text-green-700 dark:text-green-400" },
  NOT_MET: { label: "Not met", icon: X, className: "text-red-700 dark:text-red-400" },
  UNCLEAR: { label: "Unclear", icon: CircleHelp, className: "text-amber-700 dark:text-amber-400" },
} as const;

const OUTCOME: Record<Audit["state"], string> = {
  NEEDS_REVIEW: "Needs your review",
  AUTO_APPROVED: "Approved automatically",
  APPROVED: "Approved by a reviewer",
  REJECTED: "Sent back by a reviewer",
  DECIDED_ELSEWHERE: "Decided on the work order",
};

/** What the AI found in the photos, per checklist item. It recommends; people (or the policy) decide. */
function AiCheck(props: { audit: Audit | null; checklist: WorkOrder["checklist"]; notes: string | null }) {
  const { audit, checklist, notes } = props;
  if (!audit) {
    return (
      <Panel title="AI check">
        <p role="status" className="inline-flex items-center gap-2 text-sm text-muted-foreground">
          <LoaderCircle aria-hidden className="size-4 animate-spin" /> AI check running…
        </p>
      </Panel>
    );
  }
  const describe = (position: number) => checklist.find((c) => c.position === position)?.description ?? `Item ${position + 1}`;
  const sure = audit.confidence === null ? null : `${Math.round(audit.confidence * 100)}% sure`;
  const tone =
    audit.state === "NEEDS_REVIEW"
      ? "text-orange-800 dark:text-orange-300"
      : audit.state === "REJECTED"
        ? "text-red-700 dark:text-red-400"
        : "text-green-700 dark:text-green-400";
  return (
    <Panel title="AI check" action={sure && <span className="data text-xs text-muted-foreground">{sure}</span>}>
      <p className="text-sm">
        <span className={`font-medium ${tone}`}>{OUTCOME[audit.state]}</span>
        <span className="text-muted-foreground">
          {": "}
          {audit.state === "REJECTED" && audit.decisionReason
            ? audit.decisionReason
            : // "Item 1 looks not done: <evidence>": the evidence is shown under the item, so not twice
              audit.reason.replace(/^(Item \d+ (?:looks not done|is unclear from the photos)):.*$/s, "$1.")}
        </span>
      </p>
      {audit.items.length > 0 && (
        <ul className="mt-3 space-y-2.5">
          {audit.items.map((item) => {
            const v = VERDICT[item.verdict];
            return (
              <li key={item.position} className="grid grid-cols-[6.5rem_1fr] gap-x-3 text-sm">
                <span className={`inline-flex items-center gap-1.5 font-medium ${v.className}`}>
                  <v.icon aria-hidden className="size-4" /> {v.label}
                </span>
                <span>
                  {item.position + 1}. {describe(item.position)}
                  <span className="mt-0.5 block text-muted-foreground">{item.evidence}</span>
                </span>
              </li>
            );
          })}
        </ul>
      )}
      {notes && (
        <p className="mt-3 text-sm">
          <span className="text-muted-foreground">Technician's notes: </span>
          {notes}
        </p>
      )}
      <p className="mt-3 border-t border-border pt-2.5 text-xs text-muted-foreground">
        {audit.model ? `${audit.model} · prompt ${audit.promptVersion} · ` : ""}The AI recommends; you decide.
      </p>
    </Panel>
  );
}
