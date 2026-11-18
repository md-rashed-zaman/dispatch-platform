import { useQuery } from "@tanstack/react-query";
import { Sparkles } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "react-router";
import { api, ApiError, type AiUsage } from "../api";
import { ErrorNote, Panel, Skeleton } from "../components/ui";
import { count, usd } from "../format";

/** Feature ids from the API in the words the rest of the app uses for them. */
const FEATURES: Record<string, string> = {
  "skill-suggestions": "Skill suggestions",
  intake: "Describe the job",
  "completion-audit": "AI check of finished work",
};

const RANGES = [7, 30, 90] as const;

/** A company admin's view of AI use and cost (step 3.6, design-system screens.md section 4). */
export function AiUsagePage() {
  const [params, setParams] = useSearchParams();
  const days = RANGES.find((r) => String(r) === params.get("days")) ?? 30;
  const usage = useQuery({
    queryKey: ["ai-usage", days],
    queryFn: () => api<AiUsage>(`/api/ai/usage?days=${days}`),
  });
  const forbidden = usage.error instanceof ApiError && usage.error.problem.status === 403;

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">AI usage</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            What your company's AI features used, at the provider's list price. They only suggest: every form works
            without them.
          </p>
        </div>
        <div role="group" aria-label="Period" hidden={forbidden} className="inline-flex rounded-md border border-border bg-card p-0.5">
          {RANGES.map((r) => (
            <button
              key={r}
              onClick={() => setParams(r === 30 ? {} : { days: String(r) })}
              aria-pressed={r === days}
              className={`h-8 cursor-pointer rounded-[5px] px-3 text-sm transition-colors duration-150 max-sm:h-10 ${
                r === days ? "bg-primary text-white" : "text-muted-foreground hover:text-foreground"
              }`}>
              {r} days
            </button>
          ))}
        </div>
      </div>

      <div className="mt-5">
        {usage.isError && (
          <ErrorNote>
            {forbidden ? "Only your company's admins can see AI usage." : `Couldn't load AI usage: ${usage.error.message}`}
          </ErrorNote>
        )}
        {usage.isPending && <UsageSkeleton />}
        {usage.isSuccess && <Usage usage={usage.data} days={days} />}
      </div>
    </>
  );
}

function Usage({ usage, days }: { usage: AiUsage; days: number }) {
  const { today, totals } = usage;
  const didntAnswer = totals.failed + totals.skipped;
  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <BudgetTile calls={today.calls} limit={today.limit} />
        <Tile label={`Cost, ${days} days`} value={usd(totals.costUsd)} note="At list price; the free tier charges nothing" />
        <Tile label={`Calls, ${days} days`} value={count(totals.calls)} note={`${count(totals.inputTokens + totals.outputTokens)} tokens`} />
        <Tile
          label="No AI answer"
          value={count(didntAnswer)}
          note={didntAnswer === 0 ? "Every call answered" : `${count(totals.failed)} failed, ${count(totals.skipped)} not sent; the forms carried on`}
        />
      </div>

      <Panel title="Calls per day (UTC)">
        <DayChart days={usage.days} />
      </Panel>

      <Panel title="By feature">
        {usage.features.length === 0 ? (
          <div className="py-8 text-center">
            <Sparkles aria-hidden className="mx-auto size-7 text-muted-foreground" />
            <p className="mt-2 font-medium">No AI calls in the last {days} days.</p>
            <p className="mt-1 text-sm text-muted-foreground">
              They start when someone describes a job, asks for skill suggestions, or a technician submits work.
            </p>
          </div>
        ) : (
          <>
            {/* Desktop: a table. Phones: one card per feature (design system, Table). */}
            <div className="-m-4 max-md:hidden">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-border text-xs uppercase tracking-[0.04em] text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-4 py-2.5 font-medium">Feature</th>
                    <Num>Calls</Num>
                    <Num>Failed</Num>
                    <Num>Not sent</Num>
                    <Num>Tokens in</Num>
                    <Num>Tokens out</Num>
                    <Num>Cost</Num>
                  </tr>
                </thead>
                <tbody className="font-mono tabular-nums">
                  {usage.features.map((f) => (
                    <tr key={f.feature} className="h-10 border-b border-border last:border-0">
                      <th scope="row" className="px-4 font-sans font-normal">{FEATURES[f.feature] ?? f.feature}</th>
                      <td className="px-4 text-right">{count(f.calls)}</td>
                      <td className="px-4 text-right">{count(f.failed)}</td>
                      <td className="px-4 text-right">{count(f.skipped)}</td>
                      <td className="px-4 text-right">{count(f.inputTokens)}</td>
                      <td className="px-4 text-right">{count(f.outputTokens)}</td>
                      <td className="px-4 text-right">{usd(f.costUsd)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="-my-4 divide-y divide-border md:hidden">
              {usage.features.map((f) => (
                <li key={f.feature} className="py-3">
                  <p className="flex justify-between gap-3 font-medium">
                    {FEATURES[f.feature] ?? f.feature}
                    <span className="font-mono tabular-nums">{usd(f.costUsd)}</span>
                  </p>
                  <dl className="mt-1.5 grid grid-cols-3 gap-y-1 text-sm">
                    {(
                      [
                        ["Calls", f.calls],
                        ["Failed", f.failed],
                        ["Not sent", f.skipped],
                        ["Tokens in", f.inputTokens],
                        ["Tokens out", f.outputTokens],
                      ] as const
                    ).map(([term, value]) => (
                      <div key={term}>
                        <dt className="text-xs text-muted-foreground">{term}</dt>
                        <dd className="font-mono tabular-nums">{count(value)}</dd>
                      </div>
                    ))}
                  </dl>
                </li>
              ))}
            </ul>
          </>
        )}
      </Panel>
      <p className="text-xs text-muted-foreground">
        Days are UTC days, the same days the daily limit counts (it resets at 6:00 in Dhaka). "Not sent" means
        Dispatch held the call back: over the daily limit, the provider's rate limit, or the provider failing.
      </p>
    </div>
  );
}

function Num({ children }: { children: string }) {
  return <th scope="col" className="px-4 py-2.5 text-right font-medium">{children}</th>;
}

function Tile({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <section className="rounded-[10px] border border-border bg-card p-4">
      <h2 className="text-xs font-semibold uppercase tracking-[0.04em] text-muted-foreground">{label}</h2>
      <p className="mt-2 font-mono text-2xl font-medium tabular-nums">{value}</p>
      <p className="mt-1 text-sm text-muted-foreground">{note}</p>
    </section>
  );
}

/** Today's calls against the daily limit; amber from 80%, red at the limit (with words, never color alone). */
function BudgetTile({ calls, limit }: { calls: number; limit: number }) {
  const share = limit > 0 ? Math.min(1, calls / limit) : 1;
  const state = calls >= limit ? "at" : share >= 0.8 ? "near" : "ok";
  const bar = { ok: "bg-primary", near: "bg-warn", at: "bg-destructive" }[state];
  const note = {
    ok: `${count(limit - calls)} left today`,
    near: `${count(limit - calls)} left today: close to the limit`,
    at: "Limit reached: AI is off until tomorrow, forms work as usual",
  }[state];
  return (
    <section className="rounded-[10px] border border-border bg-card p-4">
      <h2 id="budget-label" className="text-xs font-semibold uppercase tracking-[0.04em] text-muted-foreground">
        Today
      </h2>
      <p className="mt-2 font-mono text-2xl font-medium tabular-nums">
        {count(calls)} <span className="font-sans text-base text-muted-foreground">of {count(limit)} calls</span>
      </p>
      <div
        role="meter"
        aria-labelledby="budget-label"
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-valuenow={Math.min(calls, limit)}
        aria-valuetext={`${calls} of ${limit} calls used today`}
        className="mt-2 h-1.5 overflow-hidden rounded-full bg-muted">
        <div className={`h-full rounded-full ${bar}`} style={{ width: `${share * 100}%` }} />
      </div>
      <p className="mt-2 text-sm text-muted-foreground">{note}</p>
    </section>
  );
}

const CHART_HEIGHT = 140;

/**
 * One bar per day. Hover or focus a bar for its numbers; the same numbers are in the table under "Show as a
 * table", so nothing is only in the picture.
 */
function DayChart({ days }: { days: AiUsage["days"] }) {
  const [active, setActive] = useState<number | null>(null);
  const max = Math.max(1, ...days.map((d) => d.calls));
  const shown = active === null ? null : days[active];
  const busiest = days.reduce((a, b) => (b.calls > a.calls ? b : a), days[0]);
  const label = (date: string) =>
    new Date(`${date}T00:00:00Z`).toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });
  const ticks = days.length <= 7 ? days.map((_, i) => i) : [0, Math.floor((days.length - 1) / 2), days.length - 1];

  return (
    <div>
      <p className="h-5 text-sm" aria-live="polite">
        {shown ? (
          <>
            <span className="font-medium">{label(shown.date)}</span>
            <span className="text-muted-foreground">
              {" "}
              · {count(shown.calls)} calls · {usd(shown.costUsd)}
            </span>
          </>
        ) : (
          <span className="text-muted-foreground">
            {busiest.calls === 0 ? "No calls yet" : `Busiest day: ${label(busiest.date)}, ${count(busiest.calls)} calls`}
          </span>
        )}
      </p>
      <div className="mt-3 flex items-end gap-[3px] border-b border-border" style={{ height: CHART_HEIGHT }} onMouseLeave={() => setActive(null)}>
        {days.map((d, i) => (
          <button
            key={d.date}
            type="button"
            aria-label={`${label(d.date)}: ${d.calls} calls, ${usd(d.costUsd)}`}
            onMouseEnter={() => setActive(i)}
            onFocus={() => setActive(i)}
            onBlur={() => setActive(null)}
            className="flex h-full min-w-0 flex-1 cursor-default items-end rounded-t-[2px] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
            <span
              aria-hidden
              className={`block w-full rounded-t-[2px] transition-colors duration-150 ${active === i ? "bg-primary-hover" : "bg-primary"}`}
              style={{ height: d.calls === 0 ? 0 : `${Math.max(2, (d.calls / max) * 100)}%` }}
            />
          </button>
        ))}
      </div>
      <div className="relative mt-1.5 h-4 font-mono text-xs text-muted-foreground tabular-nums" aria-hidden>
        {ticks.map((i) => (
          <span
            key={i}
            className="absolute whitespace-nowrap"
            style={{
              left: `${((i + 0.5) / days.length) * 100}%`,
              transform: i === 0 ? "translateX(-25%)" : i === days.length - 1 ? "translateX(-75%)" : "translateX(-50%)",
            }}>
            {label(days[i].date)}
          </span>
        ))}
      </div>
      <details className="mt-3 text-sm">
        <summary className="cursor-pointer text-muted-foreground hover:text-foreground">Show as a table</summary>
        <table className="mt-2 w-full max-w-sm text-left">
          <thead className="text-xs uppercase tracking-[0.04em] text-muted-foreground">
            <tr>
              <th scope="col" className="py-1.5 font-medium">Day</th>
              <th scope="col" className="py-1.5 text-right font-medium">Calls</th>
              <th scope="col" className="py-1.5 text-right font-medium">Cost</th>
            </tr>
          </thead>
          <tbody className="font-mono tabular-nums">
            {days.map((d) => (
              <tr key={d.date} className="border-t border-border">
                <th scope="row" className="py-1.5 font-normal">{d.date}</th>
                <td className="py-1.5 text-right">{count(d.calls)}</td>
                <td className="py-1.5 text-right">{usd(d.costUsd)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}

function UsageSkeleton() {
  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-[118px] rounded-[10px]" />
        ))}
      </div>
      <Skeleton className="h-[260px] rounded-[10px]" />
      <Skeleton className="h-[160px] rounded-[10px]" />
    </div>
  );
}
