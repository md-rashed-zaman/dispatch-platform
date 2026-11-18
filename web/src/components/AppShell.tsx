import { useQuery } from "@tanstack/react-query";
import { LogOut } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { Link, NavLink } from "react-router";
import { api, type Company, type Me } from "../api";
import { useAuth } from "../auth";
import { ago } from "../format";
import type { LiveState } from "../live";

/** The app bar: brand, the company, whether live updates are flowing, and who's signed in. */
export function AppShell(props: { live: { state: LiveState; lastEventAt: Date | null }; children: ReactNode }) {
  const { user, signOut } = useAuth();
  const company = useQuery({ queryKey: ["company"], queryFn: () => api<Company>("/api/companies/mine") });
  const me = useQuery({ queryKey: ["me"], queryFn: () => api<Me>("/api/me") });
  const admin = me.data?.roles.includes("CLIENT_ADMIN") ?? false;
  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-10 border-b border-border bg-card/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-[1280px] items-center gap-4 px-4 sm:px-6">
          <Link to="/work-orders" className="flex items-center gap-2 font-semibold tracking-tight">
            <span aria-hidden className="size-2.5 rounded-[3px] bg-primary" />
            <span className={admin ? "max-sm:sr-only" : undefined}>Dispatch</span>
          </Link>
          <span aria-hidden className="h-5 w-px bg-border max-sm:hidden" />
          <span className="truncate text-sm text-muted-foreground max-sm:hidden">{company.data?.name ?? " "}</span>
          {admin && (
            <nav aria-label="Main" className="flex items-center gap-1 text-sm">
              <ShellLink to="/work-orders">Work orders</ShellLink>
              <ShellLink to="/ai-usage">AI usage</ShellLink>
            </nav>
          )}
          <div className="ml-auto flex items-center gap-4">
            <LiveIndicator {...props.live} />
            <span className="text-sm max-md:hidden">{user.profile.name ?? user.profile.preferred_username}</span>
            <button
              onClick={signOut}
              aria-label="Sign out"
              title="Sign out"
              className="grid size-9 cursor-pointer place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground">
              <LogOut className="size-4" />
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-[1280px] px-4 py-6 sm:px-6">{props.children}</main>
    </div>
  );
}

function ShellLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        `whitespace-nowrap rounded-md px-2.5 py-1.5 transition-colors duration-150 ${
          isActive ? "bg-muted font-medium text-foreground" : "text-muted-foreground hover:text-foreground"
        }`
      }>
      {children}
    </NavLink>
  );
}

/** "Live" only while the stream is connected; otherwise says so (design system, principle 4). */
function LiveIndicator({ state, lastEventAt }: { state: LiveState; lastEventAt: Date | null }) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 5000);
    return () => clearInterval(timer);
  }, []);
  const view = {
    live: { dot: "bg-ok", label: "Live" },
    connecting: { dot: "bg-slate-400", label: "Connecting" },
    reconnecting: { dot: "bg-warn", label: "Reconnecting" },
  }[state];
  return (
    <div className="flex items-center gap-2 text-sm" title={lastEventAt ? `Last update ${lastEventAt.toLocaleTimeString()}` : undefined}>
      <span aria-hidden className={`size-2 rounded-full ${view.dot}`} />
      <span aria-live="polite">{view.label}</span>
      {lastEventAt && <span className="text-muted-foreground max-sm:hidden">· updated {ago(lastEventAt, now)}</span>}
    </div>
  );
}
