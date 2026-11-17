import type { Money, Status, TimeWindow } from "./api";

/** Labels and colors per status: design-system/dispatch/MASTER.md, "Status". */
export const STATUS: Record<Status, { label: string; className: string; attention?: boolean }> = {
  DRAFT: { label: "Draft", className: "bg-slate-100 text-slate-700 dark:bg-slate-500/15 dark:text-slate-300" },
  PUBLISHED: { label: "Finding technicians", className: "bg-blue-100 text-blue-800 dark:bg-blue-500/15 dark:text-blue-300" },
  ROUTED: { label: "Offered", className: "bg-indigo-100 text-indigo-800 dark:bg-indigo-500/15 dark:text-indigo-300" },
  ASSIGNED: { label: "Assigned", className: "bg-violet-100 text-violet-800 dark:bg-violet-500/15 dark:text-violet-300" },
  IN_PROGRESS: { label: "In progress", className: "bg-amber-100 text-amber-800 dark:bg-amber-500/15 dark:text-amber-300" },
  SUBMITTED: {
    label: "Needs your approval",
    className: "bg-orange-100 text-orange-800 dark:bg-orange-500/15 dark:text-orange-300",
    attention: true,
  },
  APPROVED: { label: "Approved", className: "bg-green-100 text-green-800 dark:bg-green-500/15 dark:text-green-300" },
  PAID: { label: "Paid", className: "bg-emerald-100 text-emerald-800 dark:bg-emerald-500/15 dark:text-emerald-300" },
  CANCELLED: { label: "Cancelled", className: "bg-slate-100 text-slate-600 dark:bg-slate-500/15 dark:text-slate-400" },
};

/** Taka, from minor units (poisha): 300000 -> ৳3,000.00 */
export function money({ minor, currency }: Money): string {
  const amount = (minor / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return currency === "BDT" ? `৳${amount}` : `${currency} ${amount}`;
}

const day = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" });
const time = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });

/** In the browser's time zone: "1 Dec, 10:00–14:00", or both dates when it spans days. */
export function timeWindow({ startsAt, endsAt }: TimeWindow): string {
  const start = new Date(startsAt);
  const end = new Date(endsAt);
  const sameDay = start.toDateString() === end.toDateString();
  return sameDay
    ? `${day.format(start)}, ${time.format(start)}–${time.format(end)}`
    : `${day.format(start)} ${time.format(start)} – ${day.format(end)} ${time.format(end)}`;
}

export function ago(date: Date, now: Date): string {
  const seconds = Math.max(0, Math.round((now.getTime() - date.getTime()) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.round(seconds / 60);
  return minutes < 60 ? `${minutes} min ago` : `${Math.round(minutes / 60)} h ago`;
}

export const shortId = (id: string) => id.slice(0, 8);
